package com.gitlab.eclipse.currentbranch

import com.gitlab.eclipse.views.sidebar.autorefresh.ActivityDebouncer
import com.gitlab.eclipse.views.sidebar.autorefresh.ActivitySource
import com.gitlab.eclipse.views.sidebar.autorefresh.Registration
import org.eclipse.swt.SWT
import org.eclipse.swt.widgets.Display
import org.eclipse.swt.widgets.Listener
import org.eclipse.swt.widgets.Shell
import org.eclipse.ui.IWorkbenchWindow

/**
 * One window's [ActivitySource] (status-bar design §4.2, decision D1 "focused only"): active = the display's
 * active shell is this window's shell, or a shell whose parent chain reaches it (a dialog opened from it). The
 * sidebar-visibility term of CI-04's `SwtActivitySource` is gone: the state refreshes whether or not the sidebar
 * is shown. Signals come from the [SharedActivationFilter] (one display filter for all windows, R7) into the
 * same [ActivityDebouncer] as CI-04, which evaluates once per burst after the platform settled the state and
 * reports changes only.
 *
 * UI thread only. A dialog opened with a null parent makes the window read inactive until it closes (then the
 * FOCUS rule refreshes) — accepted in the design.
 */
class WindowActivitySource(
  isOwnShellActive: () -> Boolean,
  defer: (Runnable) -> Unit,
  private val filter: SharedActivationFilter,
  log: (String) -> Unit,
) : ActivitySource {
  private val debouncer = ActivityDebouncer(read = isOwnShellActive, defer = defer, log = log)

  override fun isActive(): Boolean = debouncer.current()

  override fun register(onChange: (Boolean) -> Unit): Registration {
    val signal = filter.register { debouncer.signal() }
    debouncer.start(onChange)
    return Registration {
      debouncer.stop()
      signal.unregister()
    }
  }

  companion object {
    /**
     * The production source of [window] on [display]: reads `display.activeShell` against the window's shell;
     * a disposed display or shell reads inactive. Deferral is `timerExec(0, runnable)`, which reschedules an
     * already-pending runnable, so a burst stays one evaluation (as CI-04).
     */
    fun of(window: IWorkbenchWindow, display: Display, filter: SharedActivationFilter, log: (String) -> Unit) =
      WindowActivitySource(
        isOwnShellActive = { windowShellFocused(window.shell, display) },
        defer = { if (!display.isDisposed) display.timerExec(0, it) },
        filter = filter,
        log = log,
      )
  }
}

/** [shell] (the window's; null before it is created) or a dialog of it holds the display's focus. */
private fun windowShellFocused(shell: Shell?, display: Display): Boolean {
  if (display.isDisposed || shell == null || shell.isDisposed) return false
  return shellChainReaches(display.activeShell, shell) { it.parent as? Shell }
}

/** Deeper than any real dialog stack; only guards against a parent chain that loops. */
private const val MAX_SHELL_DEPTH = 64

/** Whether [start] is [target] or one of its ancestors by [parentOf] is (identity), within [MAX_SHELL_DEPTH]. */
fun <T : Any> shellChainReaches(start: T?, target: T, parentOf: (T) -> T?): Boolean {
  var current = start
  repeat(MAX_SHELL_DEPTH) {
    val shell = current ?: return false
    if (shell === target) return true
    current = parentOf(shell)
  }
  return false
}

/**
 * The production install for [SharedActivationFilter]: one `Listener` added as an `SWT.Activate` and an
 * `SWT.Deactivate` display filter (as CI-04's `SwtActivitySource`); removal skips a disposed display.
 */
fun displayActivationFilter(display: () -> Display): (() -> Unit) -> Registration = { fanOut ->
  val target = display()
  val listener = Listener { fanOut() }
  target.addFilter(SWT.Activate, listener)
  try {
    target.addFilter(SWT.Deactivate, listener)
  } catch (e: Exception) {
    if (!target.isDisposed) target.removeFilter(SWT.Activate, listener)
    throw e
  }
  Registration {
    if (!target.isDisposed) {
      target.removeFilter(SWT.Activate, listener)
      target.removeFilter(SWT.Deactivate, listener)
    }
  }
}
