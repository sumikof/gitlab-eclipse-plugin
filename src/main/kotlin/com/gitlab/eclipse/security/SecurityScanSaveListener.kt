package com.gitlab.eclipse.security

import com.gitlab.eclipse.ci.lint.sourceIdOf
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.logger
import org.eclipse.ui.IEditorInput
import org.eclipse.ui.IEditorReference
import org.eclipse.ui.IFileEditorInput
import org.eclipse.ui.IPartListener2
import org.eclipse.ui.IWindowListener
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.ui.IWorkbenchPartReference
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.preferences.ScopedPreferenceStore
import org.eclipse.ui.texteditor.IDocumentProvider
import org.eclipse.ui.texteditor.IElementStateListener
import org.eclipse.ui.texteditor.ITextEditor

/**
 * How long a `File > Revert` is given to finish before its record is treated as debris.
 *
 * A revert is three callbacks that normally arrive in the same UI turn, so anything on this scale is
 * generous. It only has to be long enough that a slow revert is still recognised, and short enough
 * that a revert whose `elementContentReplaced` never arrives cannot suppress saves indefinitely.
 */
internal const val REVERT_WINDOW_MS = 30_000L

/**
 * Remembers which elements are in the middle of a `File > Revert`, so the dirty -> clean transition
 * a revert produces can be told apart from the one a save produces.
 *
 * Deliberately pure and free of SWT: this is the whole of the exclusion rule, and the rule is the
 * part that has to be provable rather than reviewable. Remote scanning uploads the file to the
 * user's GitLab instance, and a revert is not an action the user described as "save", so sending on
 * one would be outside what opting in covered.
 *
 * [nowMillis] is injected so the expiry window is testable without waiting for it.
 */
class RevertGuard(private val nowMillis: () -> Long = System::currentTimeMillis) {
  /** element key -> when the revert was announced. */
  private val reverting = mutableMapOf<String, Long>()

  /** `elementContentAboutToBeReplaced`: a revert (or any wholesale replacement) is starting. */
  @Synchronized
  fun aboutToBeReplaced(key: String) {
    expireStale()
    reverting[key] = nowMillis()
  }

  /** `elementContentReplaced`: the replacement finished, so the record has done its job. */
  @Synchronized
  fun contentReplaced(key: String) {
    reverting.remove(key)
  }

  /**
   * Answers the dirty -> clean transition: `true` when it was a save, `false` when a revert is
   * known to be in progress for [key].
   *
   * Stale records are dropped here rather than on a timer, so nothing needs to be scheduled or
   * cancelled: a revert whose `elementContentReplaced` never arrived stops suppressing saves as soon
   * as anything asks a question again.
   */
  @Synchronized
  fun shouldScanOnClean(key: String): Boolean {
    expireStale()
    return !reverting.containsKey(key)
  }

  /** Number of live records. `internal`-by-name for tests; not part of the behaviour. */
  @Synchronized
  fun entryCountForTest(): Int = reverting.size

  private fun expireStale() {
    val cutoff = nowMillis() - REVERT_WINDOW_MS
    reverting.entries.removeAll { (_, recordedAt) -> recordedAt <= cutoff }
  }
}

/**
 * Stable key for an editor input, reusing the CI lint identity rule (`ActiveEditorContent.kt:34`):
 * workspace-relative full path for a file input, so the same basename in two projects cannot share
 * a revert record. Anything that is not an editor input has no key and is ignored.
 */
internal fun scanKeyOf(element: Any): String? = (element as? IEditorInput)?.let { sourceIdOf(it) }

/**
 * URI of an editor input in **exactly** the spelling the rest of the plugin sends the language
 * server: `IFile.locationURI.toASCIIString()`, copied from
 * `GitLabLanguageServerOpenFilesService.kt:66` (also `:89`, `:145`).
 *
 * Any other spelling would still be accepted by the server but the diagnostics it publishes back
 * could no longer be matched to the request that asked for them.
 */
internal fun scanUriOf(element: Any): String? =
  (element as? IFileEditorInput)?.file?.locationURI?.toASCIIString()

/**
 * Runs a remote security scan when the user saves the file they are looking at.
 *
 * Three gates stand between a save and an upload, in this order (design §10.2):
 *  1. `scanFileOnSave` — the save trigger's own setting. **Nothing else in the plugin reads it**,
 *     so if it is not honoured here it is not honoured at all. It is checked here rather than in
 *     [SecurityScanLauncher] on purpose: the launcher is shared with the command, and an explicit
 *     command must not be silenced by a setting about saving.
 *  2. the [RevertGuard] — a revert is not a save.
 *  3. "is this the editor the user is actually in" — a `Save All` over twenty dirty editors must
 *     upload one file, not twenty. This is the gate that bounds it, and it is the last one because
 *     it is the only one that has to touch the workbench.
 *
 * The master `SECURITY_SCAN_ENABLED` gate is not re-checked here; [SecurityScanLauncher.launch]
 * evaluates it first and again under its outbound lock.
 *
 * Everything that touches the workbench is a constructor lambda with a production default, so the
 * decision above is reachable in a headless test while the SWT surface stays a thin shell.
 */
@Suppress("TooManyFunctions")
class SecurityScanSaveListener(
  private val guard: RevertGuard = RevertGuard(),
  private val scanOnSaveEnabled: () -> Boolean = {
    service<ScopedPreferenceStore>().getBoolean(PreferenceConstants.SECURITY_SCAN_ON_SAVE)
  },
  private val keyOf: (Any) -> String? = ::scanKeyOf,
  private val uriOf: (Any) -> String? = ::scanUriOf,
  private val isActiveEditorInput: (Any) -> Boolean = { element ->
    service<PlatformUtils>().getActiveTextEditor()?.editorInput == element
  },
  private val launch: (String?, SecurityScanSource) -> SecurityScanLaunchOutcome = { uri, source ->
    service<SecurityScanLauncher>().launch(uri, source)
  },
) : IElementStateListener, IPartListener2 {
  private val log by lazy { logger<SecurityScanSaveListener>() }

  /** Providers this listener is attached to, so [uninstall] can detach from exactly those. */
  private val providers = mutableSetOf<IDocumentProvider>()

  /** Pages this listener was added to as a part listener, for the same reason. */
  private val pages = mutableSetOf<IWorkbenchPage>()

  private var installed = false

  private val windowListener = object : IWindowListener {
    override fun windowOpened(window: IWorkbenchWindow) {
      window.activePage?.let { listenTo(it) }
    }

    override fun windowClosed(window: IWorkbenchWindow) {
      window.activePage?.let { page ->
        page.removePartListener(this@SecurityScanSaveListener)
        pages.remove(page)
      }
    }

    override fun windowActivated(window: IWorkbenchWindow?) = Unit

    override fun windowDeactivated(window: IWorkbenchWindow?) = Unit
  }

  /**
   * Starts listening for saves. **Must be paired with [uninstall]** — the listener is held by every
   * document provider it attaches to, and those outlive this plugin's bundle.
   *
   * Idempotent, and never throws: a workbench that is not up yet simply leaves the window listener
   * to do the attaching later.
   */
  @Synchronized
  fun install() {
    if (installed) return
    installed = true
    try {
      val workbench = PlatformUI.getWorkbench()
      val page = workbench.activeWorkbenchWindow?.activePage
      // Same shape as GitLabLanguageServerOpenFilesService.kt:23-30: attach to the page that is
      // already there, otherwise wait for one to open.
      if (page != null) listenTo(page) else workbench.addWindowListener(windowListener)
    } catch (e: Exception) {
      // First start can run before the workbench exists. Nothing is attached, nothing leaks, and
      // saves simply do not trigger scans until something installs again. Never let start throw.
      log.warn("Security scan save trigger not installed: workbench unavailable.", e)
    }
  }

  /**
   * Stops listening and releases every reference taken by [install].
   *
   * Each removal is contained: the workbench is usually half torn down by the time this runs, and
   * one provider that has already gone must not keep the rest attached.
   */
  @Synchronized
  fun uninstall() {
    installed = false
    runCatching { PlatformUI.getWorkbench().removeWindowListener(windowListener) }
    pages.forEach { page -> runCatching { page.removePartListener(this) } }
    pages.clear()
    providers.forEach { provider -> runCatching { provider.removeElementStateListener(this) } }
    providers.clear()
  }

  private fun listenTo(page: IWorkbenchPage) {
    if (pages.add(page)) page.addPartListener(this)
    // Editors that were already open when this installed get no partOpened of their own.
    page.editorReferences.forEach { attach(it) }
  }

  override fun partOpened(partRef: IWorkbenchPartReference) = attach(partRef)

  override fun partActivated(partRef: IWorkbenchPartReference) = attach(partRef)

  /**
   * Attaches to the document provider behind [partRef].
   *
   * Providers are shared between editors of the same kind, so the set both dedupes the attach and
   * is the exact list [uninstall] has to walk.
   */
  private fun attach(partRef: IWorkbenchPartReference) {
    if (partRef !is IEditorReference) return
    // `false`: never force an editor to materialise just to listen to it.
    val part = partRef.getPart(false) ?: return
    val textEditor = part.getAdapter(ITextEditor::class.java) ?: (part as? ITextEditor) ?: return
    val provider = textEditor.documentProvider ?: return
    if (providers.add(provider)) provider.addElementStateListener(this)
  }

  /**
   * The one place a save can start an upload. UI thread; [SecurityScanLauncher.launch] returns
   * immediately and does the sending on the plugin's own scope.
   */
  override fun elementDirtyStateChanged(element: Any?, isDirty: Boolean) {
    if (isDirty) return
    val target = element ?: return
    if (!scanOnSaveEnabled()) return
    val key = keyOf(target) ?: return
    if (!guard.shouldScanOnClean(key)) return
    if (!isActiveEditorInput(target)) return
    val uri = uriOf(target) ?: return
    launch(uri, SecurityScanSource.SAVE)
  }

  override fun elementContentAboutToBeReplaced(element: Any?) {
    val key = element?.let(keyOf) ?: return
    guard.aboutToBeReplaced(key)
  }

  override fun elementContentReplaced(element: Any?) {
    val key = element?.let(keyOf) ?: return
    guard.contentReplaced(key)
  }

  override fun elementDeleted(element: Any?) {
    // A deleted element is not a save, and nothing will close out a record left against it.
    val key = element?.let(keyOf) ?: return
    guard.contentReplaced(key)
  }

  override fun elementMoved(originalElement: Any?, movedElement: Any?) {
    // The old identity is gone, so no `elementContentReplaced` can ever clear a record held
    // against it. Drop it here instead of waiting out the window.
    val key = originalElement?.let(keyOf) ?: return
    guard.contentReplaced(key)
  }
}
