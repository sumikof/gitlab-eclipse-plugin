package com.gitlab.eclipse.views.sidebar.autorefresh

import com.gitlab.eclipse.chat.quickchat.ui.UiScheduler
import com.gitlab.eclipse.chat.quickchat.ui.uiTimer
import kotlin.coroutines.cancellation.CancellationException

/**
 * The production [AutoRefreshTimer]: `Display.timerExec` through the existing [uiTimer] (delay
 * clamped into `int`, cancelled by `timerExec(-1)` on the same runnable). Unlike Quick Chat's use,
 * nothing here may throw into the refresher or the event loop: a disposed display schedules
 * nothing, and a scheduling, cancelling or action failure is logged by exception class only.
 * Throwable, as in `uiHop`: a torn-down display throws SWTError as well as SWTException.
 */
class DisplayAutoRefreshTimer(private val display: UiScheduler, private val log: (String) -> Unit) : AutoRefreshTimer {
  override fun schedule(delayMillis: Long, action: () -> Unit): Cancellable {
    if (display.isDisposed) return Cancellable { }
    val scheduled = contain("schedule") { uiTimer(display)(delayMillis) { contain("run") { action() } } }
      ?: return Cancellable { }
    return Cancellable { if (!display.isDisposed) contain("cancel") { scheduled.cancel() } }
  }

  private fun <T> contain(step: String, block: () -> T): T? =
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      runCatching { log("Current-branch auto refresh timer $step failed: ${e.javaClass.name}") }
      null
    }
}

/**
 * The background → UI hop of the auto refresh adapters (design §4), the sidebar's counterpart of
 * Quick Chat's `uiHop`: `asyncExec`, never `syncExec`; a disposed display drops the block; a
 * scheduling failure and a failure inside the block are both logged by exception class only, so
 * neither reaches the background thread (JGit's, a coroutine's) or the event loop.
 */
fun autoRefreshHop(display: UiScheduler, log: (String) -> Unit): (() -> Unit) -> Unit = { block ->
  try {
    if (!display.isDisposed) {
      display.asyncExec {
        try {
          block()
        } catch (e: Exception) {
          runCatching { log("Current-branch auto refresh UI step failed: ${e.javaClass.name}") }
        }
      }
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Throwable) {
    runCatching { log("Current-branch auto refresh UI hop failed: ${e.javaClass.name}") }
  }
}
