package com.gitlab.eclipse.chat.quickchat

import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Waits for the ASSISTANT message of one `requestId` by polling `aiMessages` (design §9.3). Only
 * completed messages are stored server side (K2), so the first match is the whole answer.
 *
 * The wait is bounded by the send's deadline, not by a count. Every poll re-captures the bound
 * connection (an OAuth refresh may have changed the token) and runs inside `runInterruptible`, and
 * the waits are [sleep] (`delay`), so cancelling the coroutine stops the poller at once. A failed
 * poll is thrown to the caller — never retried (§15.5).
 */
class QuickChatPoller(
  private val api: QuickChatApi,
  private val connections: QuickChatConnections,
  private val clock: MonotonicClock = MonotonicClock.SYSTEM,
  private val sleep: suspend (Duration) -> Unit = ::delay,
  private val firstDelay: Duration = QuickChatLimits.POLL_FIRST_DELAY,
  private val interval: Duration = QuickChatLimits.POLL_INTERVAL,
  private val requestTimeout: Duration = QuickChatLimits.REQUEST_TIMEOUT,
) {
  sealed interface Result {
    data class Answered(val content: String) : Result

    /** The answer was null, empty or blank. */
    data object EmptyAnswer : Result

    /** The answer carried `errors` (its content, if any, is not used). */
    data class Rejected(val messages: List<String>) : Result

    /** The configured instance is no longer [await]'s `instanceUrl`. */
    data object ConnectionChanged : Result

    /** The deadline passed before an answer appeared. */
    data object TimedOut : Result
  }

  /** Polls [requestId] in [threadId] on [instanceUrl] (normalized) until an answer or [deadlineNanos]. */
  suspend fun await(instanceUrl: String, requestId: String, threadId: String, deadlineNanos: Long): Result {
    val budget = RequestBudget(clock, deadlineNanos, requestTimeout)
    var wait = firstDelay
    while (true) {
      if (budget.expired()) return Result.TimedOut
      sleep(minOf(wait, budget.remainingNanos().nanoseconds))
      wait = interval
      if (budget.expired()) return Result.TimedOut
      val connection = runInterruptible { connections.captureIf(instanceUrl) } ?: return Result.ConnectionChanged
      val timeout = budget.nextTimeout() ?: return Result.TimedOut
      val nodes = runInterruptible { api.messages(connection, requestId, threadId, timeout) }
      // The server filters too; checking again here keeps another request's answer from ever showing.
      val answer = nodes.firstOrNull { it.requestId == requestId && it.role.equals(ASSISTANT_ROLE, ignoreCase = true) }
        ?: continue
      return when {
        !answer.errors.isNullOrEmpty() -> Result.Rejected(answer.errors)
        answer.content.isNullOrBlank() -> Result.EmptyAnswer
        else -> Result.Answered(answer.content)
      }
    }
  }

  private companion object {
    const val ASSISTANT_ROLE = "ASSISTANT"
  }
}
