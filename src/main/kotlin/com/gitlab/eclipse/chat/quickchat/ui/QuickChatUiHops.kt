package com.gitlab.eclipse.chat.quickchat.ui

import com.gitlab.eclipse.chat.quickchat.Cancellable
import org.eclipse.swt.widgets.Display
import kotlin.coroutines.cancellation.CancellationException

/** The two `Display` calls a Quick Chat session needs, behind a seam so the guards are testable headless. */
interface UiScheduler {
  val isDisposed: Boolean

  fun asyncExec(runnable: Runnable)

  fun timerExec(millis: Int, runnable: Runnable)
}

/** The production [UiScheduler]. */
class DisplayUiScheduler(private val display: Display) : UiScheduler {
  override val isDisposed: Boolean
    get() = display.isDisposed

  override fun asyncExec(runnable: Runnable) = display.asyncExec(runnable)

  override fun timerExec(millis: Int, runnable: Runnable) = display.timerExec(millis, runnable)
}

/**
 * The session's `runOnUi` (design §17): posts with `asyncExec`, never `syncExec`. Called from
 * background threads. A disposed display means there is no popup left, so nothing is posted; a
 * scheduling failure and a failure inside the block are both contained with the exception's class
 * name only (like `MrThreadPopupHost.hop`), so neither reaches the shared scope or the event loop.
 */
fun uiHop(scheduler: UiScheduler, log: (String) -> Unit): (() -> Unit) -> Unit = { block ->
  try {
    if (!scheduler.isDisposed) {
      scheduler.asyncExec {
        try {
          block()
        } catch (e: Exception) {
          runCatching { log("Quick Chat UI step failed: exceptionType=${e.javaClass.name}") }
        }
      }
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Throwable) {
    // Throwable: a torn-down display throws SWTError as well as SWTException.
    runCatching { log("Quick Chat UI scheduling failed: exceptionType=${e.javaClass.name}") }
  }
}

/**
 * The session's `scheduleOnUi` (design §9.2 step 8): `timerExec` with the delay clamped into its
 * `int` range, cancelled by `timerExec(-1, …)` on the same runnable. A scheduling failure is not
 * contained: it reaches `QuickChatSession.submit`, which then ends the send (§9.2 step 2) instead of
 * leaving it without a deadline.
 */
fun uiTimer(scheduler: UiScheduler): (Long, () -> Unit) -> Cancellable = { delayMillis, action ->
  val runnable = Runnable { action() }
  scheduler.timerExec(delayMillis.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(), runnable)
  Cancellable { scheduler.timerExec(-1, runnable) }
}
