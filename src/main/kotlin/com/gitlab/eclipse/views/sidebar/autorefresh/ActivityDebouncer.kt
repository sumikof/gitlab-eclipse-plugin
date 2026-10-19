package com.gitlab.eclipse.views.sidebar.autorefresh

/**
 * The SWT-free core of [SwtActivitySource] (CI-04 design §2.2, D3/D4). Every Activate, Deactivate
 * or part-visibility event is a [signal] that hands [defer] the SAME [Runnable] — production defers
 * with `Display.timerExec(0, runnable)`, which reschedules an already-pending runnable instead of
 * adding a second one — so a burst of events collapses into one evaluation after the burst. That
 * evaluation reads the state ([read]: "a shell of ours is active and the view is visible") once
 * the platform has settled it, and reports it only when it differs from the last report: moving
 * between two workbench windows (Deactivate then Activate) or opening a modal dialog reports
 * nothing.
 *
 * UI thread only. Every step is contained: a failure (typically a disposed display) is logged by
 * exception class and never reaches the SWT listener that signalled.
 */
class ActivityDebouncer(
  private val read: () -> Boolean,
  private val defer: (Runnable) -> Unit,
  private val log: (String) -> Unit,
) {
  private var onChange: ((Boolean) -> Unit)? = null
  private var last = false

  private val evaluation = Runnable { evaluate() }

  /** Starts reporting changes to [onChange], measured from the state read now. */
  fun start(onChange: (Boolean) -> Unit) {
    last = current()
    this.onChange = onChange
  }

  fun signal() {
    if (onChange == null) return
    contain("defer") { defer(evaluation) }
  }

  /** Reports nothing more, even from an evaluation that was already deferred. */
  fun stop() {
    onChange = null
  }

  /** The state now; a read that fails counts as inactive. */
  fun current(): Boolean = contain("read") { read() } ?: false

  private fun evaluate() {
    val listener = onChange ?: return
    val now = contain("read") { read() } ?: return
    if (now == last) return
    last = now
    contain("notify") { listener(now) }
  }

  private fun <T> contain(step: String, block: () -> T): T? =
    try {
      block()
    } catch (e: Exception) {
      runCatching { log("Current-branch activity $step failed: ${e.javaClass.name}") }
      null
    }
}
