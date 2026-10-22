package com.gitlab.eclipse.currentbranch

import com.gitlab.eclipse.views.sidebar.autorefresh.RefreshReason
import org.eclipse.ui.IWindowListener
import org.eclipse.ui.IWorkbench
import org.eclipse.ui.IWorkbenchWindow

/**
 * The per-window current-branch states (status-bar design §4.1): a Koin single, UI-thread confined.
 *
 * [ensureWindow] is the only creation path: idempotent, it creates and starts a window's entry once. It has two
 * callers — [activate] (the post-start UI turn: every open window, plus the window listener for later ones) and
 * the trim control's `createControl`, which can run first because loading the contribution class activates the
 * bundle during trim rendering (R3). Both are total: a failure leaves no entry and is logged by class name.
 * `windowClosed` disposes the window's entry; [dispose] (the UI shutdown turn) disposes everything and runs
 * [onDispose] (the shared activation filter). After [dispose] nothing is created again, and a window that
 * [isWindowOpen] reports closed or closing never gets an entry (a trim control rebuilt during its window's close
 * would otherwise create one after `windowClosed`, never disposed).
 */
class CurrentBranchStateService(
  private val createState: (IWorkbenchWindow) -> WindowCurrentBranchState,
  private val workbench: () -> IWorkbench,
  private val isWindowOpen: (IWorkbenchWindow) -> Boolean,
  private val onDispose: () -> Unit,
  private val log: (String) -> Unit,
) {
  private val entries = LinkedHashMap<IWorkbenchWindow, WindowCurrentBranchState>()
  private var listening: IWorkbench? = null
  private var disposed = false

  private val windowListener = object : IWindowListener {
    override fun windowActivated(window: IWorkbenchWindow) = Unit

    override fun windowDeactivated(window: IWorkbenchWindow) = Unit

    override fun windowOpened(window: IWorkbenchWindow) {
      ensureWindow(window)
    }

    override fun windowClosed(window: IWorkbenchWindow) {
      entries.remove(window)?.dispose()
    }
  }

  /** Listens for windows (once) and ensures an entry for every open one. Never throws. */
  fun activate() {
    if (disposed) return
    contain("activation") {
      if (listening == null) {
        val wb = workbench()
        wb.addWindowListener(windowListener)
        listening = wb
      }
    }
    contain("activation") { workbench().workbenchWindows.forEach { ensureWindow(it) } }
  }

  /** The window's entry, created and started on first use; null after [dispose] or when creation failed. */
  fun ensureWindow(window: IWorkbenchWindow): WindowCurrentBranchState? {
    if (disposed) return null
    entries[window]?.let { return it }
    if (!isOpen(window)) return null
    return try {
      createState(window).also { state ->
        entries[window] = state
        state.start()
      }
    } catch (e: Exception) {
      entries.remove(window)?.dispose()
      runCatching { log("Current-branch state for a window could not be created: ${e.javaClass.name}") }
      null
    }
  }

  fun stateFor(window: IWorkbenchWindow): WindowCurrentBranchState? = entries[window]

  /** A coalesced refresh of [window]'s entry (e.g. after a CI write from a handler); no entry, nothing. */
  fun requestRefresh(window: IWorkbenchWindow, reason: RefreshReason = RefreshReason.MANUAL) {
    entries[window]?.requestRefresh(reason)
  }

  /** Disposes every entry and the window listener. Idempotent; never throws. */
  fun dispose() {
    if (disposed) return
    disposed = true
    listening?.let { wb -> contain("shutdown") { wb.removeWindowListener(windowListener) } }
    listening = null
    entries.values.toList().forEach { state -> contain("shutdown") { state.dispose() } }
    entries.clear()
    contain("shutdown") { onDispose() }
  }

  /** T3b: never an entry for a closed or closing window; a check that fails counts as closed. */
  private fun isOpen(window: IWorkbenchWindow): Boolean =
    try {
      isWindowOpen(window)
    } catch (@Suppress("SwallowedException") e: Exception) {
      false
    }

  private fun contain(step: String, block: () -> Unit) {
    try {
      block()
    } catch (e: Exception) {
      runCatching { log("Current-branch state $step failed: ${e.javaClass.name}") }
    }
  }
}
