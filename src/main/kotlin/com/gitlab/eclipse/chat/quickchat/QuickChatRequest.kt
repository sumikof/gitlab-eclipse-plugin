package com.gitlab.eclipse.chat.quickchat

import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Time and capacity limits of Quick Chat (design §15, brief I3). Collaborators take these as defaults. */
object QuickChatLimits {
  val POLL_FIRST_DELAY: Duration = 1.seconds
  val POLL_INTERVAL: Duration = 3.seconds

  /** Per HTTP request (R2); always further capped by the time left to the deadline. */
  val REQUEST_TIMEOUT: Duration = 25.seconds
  val ANSWER_DEADLINE: Duration = 120.seconds
  val CLEAR_DEADLINE: Duration = 30.seconds
  const val MAX_DETACHED: Int = 4
}

/** A monotonic clock in nanoseconds (design §15.1); injectable so tests control time. */
fun interface MonotonicClock {
  fun nanoTime(): Long

  companion object {
    val SYSTEM: MonotonicClock = MonotonicClock(System::nanoTime)
  }
}

/**
 * Everything the background needs for one send (design §9.2 step 6), and nothing that reaches the UI:
 * immutable values plus the shared [gate]. [anchorFile] is null when the editor input has no
 * location; [deadlineNanos] is on the [MonotonicClock] the UI used to fix it.
 */
data class QuickChatRequest(
  val context: QuickChatContext,
  val anchorFile: File?,
  val binding: ConversationBinding?,
  val deadlineNanos: Long,
  val gate: SendGate,
)

/**
 * The time left to one deadline on [clock] (design §15.2). Every HTTP request of a send is bounded
 * by [nextTimeout] = min([requestTimeout], time left); none is made once the deadline has passed.
 */
class RequestBudget(
  private val clock: MonotonicClock,
  private val deadlineNanos: Long,
  private val requestTimeout: Duration,
) {
  fun remainingNanos(): Long = deadlineNanos - clock.nanoTime()

  fun expired(): Boolean = remainingNanos() <= 0

  /** The timeout for the next HTTP request, or null when the deadline has passed. */
  fun nextTimeout(): java.time.Duration? {
    val remaining = remainingNanos()
    if (remaining <= 0) return null
    return java.time.Duration.ofNanos(minOf(remaining, requestTimeout.inWholeNanoseconds))
  }
}
