package com.gitlab.eclipse.codesuggestions.status

import com.gitlab.eclipse.lsp.lifecycle.LanguageServerLifecycleListener
import com.gitlab.eclipse.lsp.lifecycle.LanguageServerLifecycleState
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import org.eclipse.jface.preference.IPreferenceStore
import org.eclipse.jface.util.IPropertyChangeListener
import org.eclipse.swt.SWTException
import org.eclipse.ui.IEditorReference
import org.eclipse.ui.IPartListener2
import org.eclipse.ui.ISourceProvider
import org.eclipse.ui.ISourceProviderListener
import org.eclipse.ui.IWindowListener
import org.eclipse.ui.IWorkbench
import org.eclipse.ui.IWorkbenchPartReference
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.commands.ICommandService
import org.koin.core.context.GlobalContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Re-renders the Duo trim item and the Code Suggestions status menu item of every window
 * (design §8.3).
 *
 * [request] is thread-agnostic and never blocks: at most one refresh is pending at a time
 * ([pending]), so a burst of triggers costs one UI turn. The pending flag is cleared before the
 * refresh runs, so a trigger that arrives during a refresh schedules exactly one more.
 *
 * Nothing is dispatched or refreshed once the lifecycle store is closed, the workbench is not
 * running or closing, or [dispose] ran; every failure of the hop or of a refresh is contained.
 *
 * Triggers: lifecycle transitions ([onTransition]), Code Suggestions state changes and toggles
 * (callers of [request]), and — after [installWindowListeners] — editor activation in any window,
 * window open, theme change, the auth source provider and Code Suggestions preference changes.
 *
 * @param uiDispatch hands a runnable to the UI thread (`asyncExec`; never `syncExec`).
 * @param refreshElements refreshes the elements of one command id on the UI thread.
 * @param authSource the workbench-created auth source provider, or null when unavailable.
 * @param preferenceStore the plugin preference store, or null when unavailable.
 */
class DuoStatusRefresher(
  private val isStoreClosed: () -> Boolean,
  private val isWorkbenchAvailable: () -> Boolean = ::workbenchAvailable,
  private val uiDispatch: (Runnable) -> Unit = { currentDisplay.asyncExec(it) },
  private val refreshElements: (String) -> Unit = ::refreshCommandElements,
  private val workbench: () -> IWorkbench = PlatformUI::getWorkbench,
  private val authSource: () -> ISourceProvider? = { null },
  private val preferenceStore: () -> IPreferenceStore? = { null },
) : LanguageServerLifecycleListener {
  companion object {
    const val SHOW_PLUGIN_STATUS_COMMAND_ID = "gitlab-eclipse-plugin.commands.showPluginStatus"
    const val CODE_SUGGESTIONS_STATUS_COMMAND_ID = "gitlab-eclipse-plugin.commands.codeSuggestionsStatus"
    val COMMAND_IDS = listOf(SHOW_PLUGIN_STATUS_COMMAND_ID, CODE_SUGGESTIONS_STATUS_COMMAND_ID)

    private val WATCHED_PREFERENCES = setOf(
      PreferenceConstants.CODE_SUGGESTIONS_ENABLED,
      PreferenceConstants.CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES,
      PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES,
    )
  }

  private val pending = AtomicBoolean(false)
  private val installed = AtomicBoolean(false)

  @Volatile
  private var disposed = false

  /** Undo actions of everything [installWindowListeners] registered, run by [dispose]. */
  private val registrations = CopyOnWriteArrayList<() -> Unit>()

  /** Windows whose part service carries [partListener]. */
  private val trackedWindows = ConcurrentHashMap.newKeySet<IWorkbenchWindow>()

  private val partListener = object : IPartListener2 {
    override fun partActivated(partRef: IWorkbenchPartReference) = onEditorChange(partRef)
    override fun partBroughtToTop(partRef: IWorkbenchPartReference) = onEditorChange(partRef)
    override fun partClosed(partRef: IWorkbenchPartReference) = onEditorChange(partRef)
    override fun partInputChanged(partRef: IWorkbenchPartReference) = onEditorChange(partRef)
  }

  private val windowListener = object : IWindowListener {
    override fun windowActivated(window: IWorkbenchWindow) = Unit
    override fun windowDeactivated(window: IWorkbenchWindow) = Unit

    override fun windowOpened(window: IWorkbenchWindow) {
      track(window)
      // F7: the new window's trim starts with the static icon until it is refreshed.
      request()
    }

    override fun windowClosed(window: IWorkbenchWindow) = untrack(window)
  }

  private val themeListener = IPropertyChangeListener { request() }

  private val preferenceListener = IPropertyChangeListener { event ->
    if (event.property in WATCHED_PREFERENCES) request()
  }

  private val sourceListener = object : ISourceProviderListener {
    override fun sourceChanged(sourcePriority: Int, sourceValuesByName: MutableMap<Any?, Any?>?) = request()
    override fun sourceChanged(sourcePriority: Int, sourceName: String?, sourceValue: Any?) = request()
  }

  override fun onTransition(old: LanguageServerLifecycleState, new: LanguageServerLifecycleState) = request()

  /** Schedules one refresh of every window's Duo status elements unless one is already pending. */
  fun request() {
    if (!canRefresh()) return
    if (!pending.compareAndSet(false, true)) return
    try {
      uiDispatch(Runnable(::refresh))
    } catch (e: RuntimeException) {
      pending.set(false)
      logUnexpected(e)
    }
  }

  /**
   * Registers the window, part, theme, auth source and preference listeners; call once on the UI
   * thread at startup (window listeners must be added there). A failing step is skipped without
   * preventing the others. No-op after [dispose] or when already installed.
   */
  fun installWindowListeners() {
    if (disposed || !installed.compareAndSet(false, true)) return
    step {
      val wb = workbench()
      wb.addWindowListener(windowListener)
      registrations += { wb.removeWindowListener(windowListener) }
      wb.workbenchWindows.forEach(::track)
    }
    step {
      val themeManager = workbench().themeManager
      themeManager.addPropertyChangeListener(themeListener)
      registrations += { themeManager.removePropertyChangeListener(themeListener) }
    }
    step {
      authSource()?.let { source ->
        source.addSourceProviderListener(sourceListener)
        registrations += { source.removeSourceProviderListener(sourceListener) }
      }
    }
    step {
      preferenceStore()?.let { store ->
        store.addPropertyChangeListener(preferenceListener)
        registrations += { store.removePropertyChangeListener(preferenceListener) }
      }
    }
  }

  /** Stops refreshing and removes every listener [installWindowListeners] added (I6). Idempotent. */
  fun dispose() {
    disposed = true
    trackedWindows.toList().forEach(::untrack)
    registrations.reversed().forEach { remove -> step(remove) }
    registrations.clear()
  }

  private fun refresh() {
    pending.set(false)
    if (!canRefresh()) return
    for (id in COMMAND_IDS) {
      try {
        refreshElements(id)
      } catch (e: RuntimeException) {
        logUnexpected(e)
      }
    }
  }

  private fun canRefresh(): Boolean =
    try {
      !disposed && !isStoreClosed() && isWorkbenchAvailable()
    } catch (e: RuntimeException) {
      logUnexpected(e)
      false
    }

  private fun onEditorChange(partRef: IWorkbenchPartReference) {
    if (partRef is IEditorReference) request()
  }

  private fun track(window: IWorkbenchWindow) {
    if (disposed || !trackedWindows.add(window)) return
    step { window.partService.addPartListener(partListener) }
  }

  private fun untrack(window: IWorkbenchWindow) {
    if (!trackedWindows.remove(window)) return
    step { window.partService.removePartListener(partListener) }
  }

  private inline fun step(action: () -> Unit) {
    try {
      action()
    } catch (e: RuntimeException) {
      logUnexpected(e)
    }
  }
}

/** Display disposal and workbench teardown are expected races and stay silent; anything else is logged by class. */
private fun logUnexpected(e: RuntimeException) {
  if (e is SWTException || e is IllegalStateException) return
  runCatching { logger<DuoStatusRefresher>().warn("Duo status refresh failed (${e.javaClass.simpleName}).") }
}

private fun workbenchAvailable(): Boolean = PlatformUI.isWorkbenchRunning() && !PlatformUI.getWorkbench().isClosing

private fun refreshCommandElements(commandId: String) {
  PlatformUI
    .getWorkbench()
    .getService(ICommandService::class.java)
    ?.refreshElements(commandId, emptyMap<Any, Any>())
}

/**
 * Requests a Duo status refresh from a UI action (the Code Suggestions toggles, whose `putValue`
 * fires no preference event). No-op when the refresher is not available.
 */
fun requestDuoStatusRefresh() {
  GlobalContext.getOrNull()?.getOrNull<DuoStatusRefresher>()?.request()
}
