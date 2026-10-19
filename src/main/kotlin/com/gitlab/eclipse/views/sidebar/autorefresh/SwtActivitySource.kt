package com.gitlab.eclipse.views.sidebar.autorefresh

import org.eclipse.swt.SWT
import org.eclipse.swt.widgets.Display
import org.eclipse.swt.widgets.Listener
import org.eclipse.ui.IPartListener2
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.ui.IWorkbenchPart
import org.eclipse.ui.IWorkbenchPartReference

/**
 * The production [ActivitySource] (CI-04 design §2.2): "active" = a shell of this application has
 * focus (`display.activeShell != null`, the EGit `ApplicationActiveListener` approach — not
 * `IWindowListener`, which fires for modal dialogs and twice when moving between windows) AND
 * [part] is visible on [page]. Display-wide `SWT.Activate` / `SWT.Deactivate` filters and the
 * part's `partVisible` / `partHidden` only signal an [ActivityDebouncer], which evaluates the state
 * once per burst, after the platform has settled it, and reports changes only.
 *
 * UI thread only (registration happens in `createPartControl`, removal in `dispose`). Every SWT
 * callback is contained by the debouncer; a disposed display reads as inactive and is not touched.
 * Verified on real SWT only (manual steps in the PR): nothing here runs headless.
 */
class SwtActivitySource(
  private val display: Display,
  private val page: IWorkbenchPage,
  private val part: IWorkbenchPart,
  private val log: (String) -> Unit,
) : ActivitySource {
  private val debouncer = ActivityDebouncer(
    read = { !display.isDisposed && display.activeShell != null && page.isPartVisible(part) },
    // timerExec reschedules a runnable that is already pending, so a burst stays one evaluation.
    defer = { if (!display.isDisposed) display.timerExec(0, it) },
    log = log,
  )

  override fun isActive(): Boolean = debouncer.current()

  override fun register(onChange: (Boolean) -> Unit): Registration {
    val filter = Listener { debouncer.signal() }
    val partListener = object : IPartListener2 {
      override fun partVisible(ref: IWorkbenchPartReference) = signalIfOurs(ref)

      override fun partHidden(ref: IWorkbenchPartReference) = signalIfOurs(ref)

      private fun signalIfOurs(ref: IWorkbenchPartReference) {
        if (ref.getPart(false) === part) debouncer.signal()
      }
    }
    val undo = mutableListOf<() -> Unit>()
    try {
      display.addFilter(SWT.Activate, filter)
      undo += { if (!display.isDisposed) display.removeFilter(SWT.Activate, filter) }
      display.addFilter(SWT.Deactivate, filter)
      undo += { if (!display.isDisposed) display.removeFilter(SWT.Deactivate, filter) }
      page.addPartListener(partListener)
      undo += { page.removePartListener(partListener) }
    } catch (e: Exception) {
      undoAll(undo)
      throw e // the refresher's start() rolls back and logs the class name
    }
    debouncer.start(onChange)
    return Registration {
      debouncer.stop()
      undoAll(undo)
    }
  }

  /** Each removal on its own: one failing must not leave the others installed. */
  private fun undoAll(steps: List<() -> Unit>) =
    steps.forEach { step ->
      try {
        step()
      } catch (e: Exception) {
        runCatching { log("Current-branch activity listener removal failed: ${e.javaClass.name}") }
      }
    }
}
