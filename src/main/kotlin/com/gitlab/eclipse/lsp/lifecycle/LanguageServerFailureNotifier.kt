package com.gitlab.eclipse.lsp.lifecycle

import com.gitlab.eclipse.utils.ActionNotifications
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import org.eclipse.swt.SWT
import org.eclipse.swt.SWTException
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.handlers.IHandlerService

private const val POPUP_TITLE = "GitLab Duo"
private const val RESTART_COMMAND_ID = "gitlab-eclipse-plugin.commands.RestartLanguageServer"
private const val SHOW_OUTPUT_COMMAND_ID = "gitlab-eclipse-plugin.commands.ShowOutput"

/**
 * The failure edge worth a popup (design §5.4): the generation's first FAILED, of a startup attempt
 * or a crash of any attempt (a crash after a successful restart is still unexpected). A failed
 * restart attempt is reported by the Restart handler's own toast instead (AC-L5).
 */
internal fun shouldNotify(old: LanguageServerLifecycleState, new: LanguageServerLifecycleState): Boolean =
  new.phase == LanguageServerPhase.FAILED &&
    old.phase != LanguageServerPhase.FAILED &&
    old.generation == new.generation &&
    (new.origin == StartOrigin.STARTUP || new.failure == LanguageServerFailureKind.CRASHED)

/** Popup text (design §5.4): VS Code's start-failure text, and an Eclipse-specific crash text. */
internal fun failureMessage(kind: LanguageServerFailureKind): String =
  if (kind == LanguageServerFailureKind.CRASHED) {
    "GitLab Language Server stopped unexpectedly"
  } else {
    "GitLab Language Server failed to start"
  }

/**
 * Shows an error popup with "Restart Language Server" / "Show Logs" when the Language Server fails to
 * start or crashes (LS-02). A lifecycle listener: [onTransition] runs on the mutating thread (which
 * holds the provider's lifecycle lock), so it only decides on the edge and hands the rest to
 * [uiDispatch]. The UI runnable re-reads the store's snapshot, because two transitions on different
 * threads can be delivered out of order: it shows only while that generation is still FAILED, the
 * store is open and the workbench is up. The popup stays until the user closes it or, best-effort,
 * until the state leaves that generation's FAILED.
 *
 * [present] opens the popup on the UI thread and returns what closes it (null when nothing opened).
 * [lastShown] is confined to the UI thread; [shownGeneration] is its cross-thread hint.
 */
class LanguageServerFailureNotifier(
  private val store: LanguageServerLifecycleStore,
  private val uiDispatch: (Runnable) -> Unit = { currentDisplay.asyncExec(it) },
  private val workbenchAvailable: () -> Boolean = ::isWorkbenchAvailable,
  private val present: (LanguageServerFailureKind) -> AutoCloseable? = ::presentFailure,
) : LanguageServerLifecycleListener {
  private var lastShown: AutoCloseable? = null

  @Volatile
  private var shownGeneration: Long = NONE

  override fun onTransition(old: LanguageServerLifecycleState, new: LanguageServerLifecycleState) {
    if (shouldNotify(old, new)) {
      dispatch { showIfStillFailed(new.generation) }
    } else {
      val shown = shownGeneration
      if (shown != NONE && !(new.phase == LanguageServerPhase.FAILED && new.generation == shown)) {
        dispatch { closeIfLeft() }
      }
    }
  }

  private fun showIfStillFailed(generation: Long) {
    val current = store.snapshot()
    if (store.isClosed || current.phase != LanguageServerPhase.FAILED || current.generation != generation) return
    val kind = current.failure ?: return
    if (!workbenchAvailable()) return
    closeShown()
    lastShown = present(kind)
    shownGeneration = if (lastShown != null) generation else NONE
    // A transition that landed while the popup was opening saw no shown generation yet and so
    // dispatched no close: re-check now and close it (best-effort).
    closeIfLeft()
  }

  private fun closeIfLeft() {
    val current = store.snapshot()
    if (current.phase == LanguageServerPhase.FAILED && current.generation == shownGeneration) return
    closeShown()
  }

  private fun closeShown() {
    val shown = lastShown ?: return
    lastShown = null
    shownGeneration = NONE
    try {
      shown.close()
    } catch (e: SWTException) {
      logger<LanguageServerFailureNotifier>().warn("Could not close the failure popup (${e.javaClass.simpleName}).")
    }
  }

  /**
   * Total: the mutating thread must never see an exception from here. A disposed display rejects
   * asyncExec with [SWTException], and a torn-down workbench fails the display lookup with
   * [IllegalStateException]; either way there is no UI left to notify. Inside the runnable an
   * [SWTException] (display disposed mid-turn) is swallowed for the same reason.
   */
  private fun dispatch(block: () -> Unit) {
    try {
      uiDispatch(
        Runnable {
          try {
            block()
          } catch (ignored: SWTException) {
            /* display disposed mid-turn: nothing to show */
          }
        },
      )
    } catch (ignored: SWTException) {
      /* asyncExec on a disposed display: nothing to show */
    } catch (ignored: IllegalStateException) {
      /* workbench torn down: nothing to show */
    }
  }

  private companion object {
    const val NONE = -1L
  }
}

private fun isWorkbenchAvailable(): Boolean = PlatformUI.isWorkbenchRunning() && !PlatformUI.getWorkbench().isClosing

/** Default [LanguageServerFailureNotifier] presenter: runs inside the notifier's UI runnable. */
private fun presentFailure(kind: LanguageServerFailureKind): AutoCloseable =
  ActionNotifications.show(
    title = POPUP_TITLE,
    message = failureMessage(kind),
    severity = SWT.ICON_ERROR,
    links = listOf(
      "Restart Language Server" to { executeCommand(RESTART_COMMAND_ID) },
      "Show Logs" to { executeCommand(SHOW_OUTPUT_COMMAND_ID) },
    ),
    // The error popup stays until closed (AbstractNotificationPopup auto-closes only when > 0).
    autoCloseMillis = 0,
    // Already on the UI thread: the decision above and the popup stay in the same UI turn.
    onUiThread = { it.run() },
  )

/**
 * Runs a plugin command through the workbench-level handler service (no window context needed,
 * design §15 F8). The Restart handler moves `restart()` off the UI thread itself.
 */
private fun executeCommand(commandId: String) {
  try {
    PlatformUI.getWorkbench().getService(IHandlerService::class.java).executeCommand(commandId, null)
  } catch (e: Exception) {
    logger<LanguageServerFailureNotifier>().warn("Failed to execute $commandId (${e.javaClass.simpleName}).")
  }
}
