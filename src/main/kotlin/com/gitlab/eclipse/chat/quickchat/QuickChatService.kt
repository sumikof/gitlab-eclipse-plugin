package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.GraphQlException
import com.gitlab.eclipse.api.UnstableConnectionException
import com.gitlab.eclipse.ci.actions.normalizeInstanceUrl
import com.gitlab.eclipse.utils.logger
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException
import java.nio.channels.UnresolvedAddressException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/** The first HTTP status of the server-error class (5xx). */
private const val FIRST_SERVER_ERROR_STATUS = 500

/** What became of a background `/clear` / `/reset` (design §9.4); only logged, never shown. */
enum class ClearResult { NO_THREAD, SENT, REJECTED, CONNECTION_CHANGED, TIMED_OUT, FAILED }

/**
 * The background half of one Quick Chat send (design §9.2 w1–w7) and of `/clear` / `/reset`
 * (§9.4, §15.4).
 *
 * Reads only the immutable request and the shared [SendGate]; never touches the conversation. Every
 * blocking call (connection capture, project resolution, HTTP) runs inside `runInterruptible`, so
 * cancelling the coroutine interrupts it (E12, E14). Every exception except [CancellationException]
 * becomes a [QuickChatOutcome]. Logs only outcome kinds, HTTP status, correlation ids, requestIds,
 * counts and exception class names — never the question, answer, file or server text (NFR-4).
 *
 * TooManyFunctions is suppressed: the answer stream (streaming design §9.1 s1, §19) adds an open
 * and a summary step that share the send's private [Progress] and budget; moving them out would
 * expose that per-send state.
 */
@Suppress("TooManyFunctions")
class QuickChatService(
  private val api: QuickChatApi,
  private val connections: QuickChatConnections,
  private val preflight: QuickChatPreflight,
  private val poller: QuickChatPoller,
  private val clock: MonotonicClock = MonotonicClock.SYSTEM,
  private val requestTimeout: Duration = QuickChatLimits.REQUEST_TIMEOUT,
  private val clearDeadline: Duration = QuickChatLimits.CLEAR_DEADLINE,
  private val newSubscriptionId: () -> String = { UUID.randomUUID().toString() },
  private val streams: QuickChatStreams? = null,
) {
  private val logger by lazy { logger<QuickChatService>() }

  /** Where a send is; decides how a failure is classified (design §16). */
  private enum class Stage { BEFORE_SEND, SENDING, AFTER_SEND }

  /** Mutable progress of one [ask], so a failure can still report what was already settled. */
  private class Progress {
    var stage = Stage.BEFORE_SEND
    var update: BindingUpdate? = null
    var requestId: String? = null

    /** The summary's `open=` part (streaming design §19); null when no stream was tried. */
    var stream: String? = null
  }

  /**
   * Runs one send. Returns null only when [SendGate.tryBeginSend] is refused at w4: the UI already
   * finished this send (and reported "nothing was sent"), so there is no result to deliver.
   */
  suspend fun ask(request: QuickChatRequest): QuickChatOutcome? {
    val budget = RequestBudget(clock, request.deadlineNanos, requestTimeout)
    val progress = Progress()
    val outcome = try {
      send(request, budget, progress)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      classify(e, progress, budget)
    }
    if (outcome != null) logOutcome(outcome, progress, request.binding != null)
    return outcome
  }

  private suspend fun send(request: QuickChatRequest, budget: RequestBudget, progress: Progress): QuickChatOutcome? {
    // w1 / w2
    if (budget.expired()) return QuickChatOutcome.TimedOut(beforeSend = true, update = null)
    val binding = request.binding
    val connection = runInterruptible {
      if (binding != null) connections.captureIf(binding.instanceUrl) else connections.capture()
    } ?: return QuickChatOutcome.ConnectionChanged

    // w3
    if (budget.expired()) return QuickChatOutcome.TimedOut(beforeSend = true, update = null)
    val proceed = when (val result = preflight.check(connection, request.anchorFile, binding, budget)) {
      is QuickChatPreflight.Result.Stop -> return result.outcome
      is QuickChatPreflight.Result.Proceed -> result
    }
    // A changed project starts a new conversation: the old thread belongs to the old project.
    val threadId = if (proceed.projectChanged) null else binding?.threadId
    val instanceUrl = normalizeInstanceUrl(connection.instanceUrl)
    val base = BindingUpdate(instanceUrl, proceed.preflight, threadId, proceed.projectChanged)
    progress.update = base

    // s1 (streaming design §9.1): subscribe before aiAction, with the same clientSubscriptionId.
    val subscriptionId = newSubscriptionId()
    val stream = openStream(connection, subscriptionId, budget, request.onProgress, progress)
    try {
      // w1 after s1, which spends time, and before the gate: passing the gate and then not sending
      // would leave it Sending for nothing.
      val timeout = budget.nextTimeout() ?: return QuickChatOutcome.TimedOut(beforeSend = true, update = base)
      // w4
      if (!request.gate.tryBeginSend()) return null
      progress.stage = Stage.SENDING
      val response = runInterruptible {
        api.ask(
          connection,
          request.context.question,
          request.context.currentFile,
          proceed.preflight.resourceId,
          threadId,
          subscriptionId,
          timeout,
        )
      }
      return afterAsk(response, base, request, progress, stream)
    } finally {
      stream?.close()
      progress.stream?.let { logStream(it, stream, progress.requestId) }
    }
  }

  /**
   * s1: the stream, or null for none. Never throws except [CancellationException]: the stream is a
   * display aid only (FR-S4), so every failure is "no stream" and the send goes on.
   */
  private suspend fun openStream(
    connection: ConnectionSnapshot,
    subscriptionId: String,
    budget: RequestBudget,
    onProgress: (() -> String) -> Unit,
    progress: Progress,
  ): AiCompletionStream? {
    val opener = streams ?: return null
    val wait = minOf(QuickChatStreamLimits.SUBSCRIBE_WAIT, budget.remainingNanos().nanoseconds)
    if (!wait.isPositive()) return null
    // Set only once open returned: progress before that has no stream to read from.
    val holder = AtomicReference<AiCompletionStream?>()
    val result = try {
      opener.open(connection, subscriptionId, wait) { holder.get()?.let { onProgress(it::displayText) } }
    } catch (e: CancellationException) {
      throw e
    } catch (
      @Suppress("TooGenericExceptionCaught") // A foreign opener; the stream must never break the send.
      e: Exception,
    ) {
      progress.stream = "OPEN_THREW:${e.javaClass.name}"
      return null
    }
    return when (result) {
      is StreamOpenResult.Opened -> result.stream.also {
        holder.set(it)
        progress.stream = "CONFIRMED"
      }
      is StreamOpenResult.Failed -> {
        progress.stream = result.reason.name
        null
      }
    }
  }

  /** §19: one line per send that tried a stream — kinds and counts only, never text, token or user id. */
  private fun logStream(open: String, stream: AiCompletionStream?, requestId: String?) {
    logger.info(
      "Quick Chat stream ended: open=$open stop=${stream?.stopReason ?: "none"} " +
        "chunks=${stream?.chunksAccepted ?: 0} seriesResets=${stream?.seriesResets ?: 0} " +
        "final=${stream?.finalReceived ?: false} requestId=$requestId",
    )
  }

  private suspend fun afterAsk(
    response: AskResponse,
    base: BindingUpdate,
    request: QuickChatRequest,
    progress: Progress,
    stream: AiCompletionStream?,
  ): QuickChatOutcome {
    // w5
    val returned = response.threadId?.let { base.copy(threadId = it) } ?: base
    progress.update = returned
    progress.requestId = response.requestId
    if (response.errors.isNotEmpty()) return QuickChatOutcome.ServerRejected(response.errors, returned)
    val requestId = response.requestId ?: return QuickChatOutcome.ServerRejected(emptyList(), returned)
    val threadId = response.threadId ?: return QuickChatOutcome.Unsupported(null, returned)
    // The stream shows only this send's chunks from here on (§9.1 step 5); display only (FR-S3).
    stream?.confirmRequestId(requestId)
    request.gate.markSent(returned)
    progress.stage = Stage.AFTER_SEND

    // w6
    return when (val result = poller.await(returned.instanceUrl, requestId, threadId, request.deadlineNanos)) {
      is QuickChatPoller.Result.Answered -> QuickChatOutcome.Answered(result.content, returned)
      QuickChatPoller.Result.EmptyAnswer -> QuickChatOutcome.EmptyAnswer(returned)
      is QuickChatPoller.Result.Rejected -> QuickChatOutcome.ServerRejected(result.messages, returned)
      QuickChatPoller.Result.ConnectionChanged -> QuickChatOutcome.ConnectionChanged
      QuickChatPoller.Result.TimedOut -> QuickChatOutcome.TimedOut(beforeSend = false, update = returned)
    }
  }

  /**
   * Design §16, pinned by tests: a failure known to precede the connection → [QuickChatOutcome.TransportFailed];
   * one after `aiAction` may have reached GitLab (timeout, broken stream, uninterpretable reply)
   * → [QuickChatOutcome.MaybeSent]. A request timeout once the deadline has passed is the deadline.
   */
  private fun classify(e: Exception, progress: Progress, budget: RequestBudget): QuickChatOutcome {
    val update = progress.update
    if (progress.stage == Stage.SENDING && mayHaveRun(e)) return QuickChatOutcome.MaybeSent(update)
    val known = knownTransportFailure(e, update)
    if (known != null) return known
    val deadlinePassed = e is HttpTimeoutException && budget.expired()
    return when {
      progress.stage == Stage.SENDING -> QuickChatOutcome.MaybeSent(update)
      deadlinePassed -> QuickChatOutcome.TimedOut(beforeSend = progress.stage == Stage.BEFORE_SEND, update = update)
      else -> QuickChatOutcome.TransportFailed(stageFreeKind(e), null, null, update)
    }
  }

  /**
   * During `aiAction`, a GraphQL error that came with a `data` key, or a server-side (5xx) HTTP
   * failure, may follow a mutation that already ran: calling it failed would invite a double send.
   * A GraphQL error without data and a 4xx are definite rejections and stay failures.
   */
  private fun mayHaveRun(e: Exception): Boolean = when (e) {
    is GraphQlException -> e.hasDataKey
    is GitLabApiException -> e.statusCode >= FIRST_SERVER_ERROR_STATUS
    else -> false
  }

  /** Definite failures: nothing (more) went out, or the server said no before running anything. */
  private fun knownTransportFailure(e: Exception, update: BindingUpdate?): QuickChatOutcome? {
    val kind = when (e) {
      is ConnectException, is UnresolvedAddressException, is HttpConnectTimeoutException -> TransportKind.CONNECT
      is GitLabApiException ->
        return QuickChatOutcome.TransportFailed(TransportKind.HTTP, e.statusCode, e.correlationId, update)
      is GraphQlException ->
        return QuickChatOutcome.TransportFailed(TransportKind.GRAPHQL, null, e.correlationId, update)
      is UnstableConnectionException -> TransportKind.UNSTABLE_CONNECTION
      else -> return null
    }
    return QuickChatOutcome.TransportFailed(kind, null, null, update)
  }

  private fun stageFreeKind(e: Exception): TransportKind = when (e) {
    is JsonSyntaxException -> TransportKind.INVALID_RESPONSE
    is HttpTimeoutException -> TransportKind.TIMEOUT
    is IOException -> TransportKind.IO
    else -> TransportKind.UNEXPECTED
  }

  private fun logOutcome(outcome: QuickChatOutcome, progress: Progress, bound: Boolean) {
    if (outcome is QuickChatOutcome.Answered) return
    val detail = when (outcome) {
      is QuickChatOutcome.TransportFailed ->
        " kind=${outcome.kind} status=${outcome.status} correlationId=${outcome.correlationId}"
      is QuickChatOutcome.ServerRejected -> " errors=${outcome.messages.size}"
      is QuickChatOutcome.ProjectCheckFailed -> " kind=${outcome.kind}"
      is QuickChatOutcome.TimedOut -> " beforeSend=${outcome.beforeSend}"
      else -> ""
    }
    logger.info(
      "Quick Chat send ended: ${outcome.javaClass.simpleName}$detail stage=${progress.stage} " +
        "requestId=${progress.requestId} bound=$bound",
    )
  }

  /**
   * Sends `/clear` or `/reset` for [binding]'s thread (design §9.4) under [clearDeadline], on the
   * bound instance only. Nobody waits for it; the result is logged (error counts, never text).
   */
  suspend fun clear(binding: ConversationBinding, command: QuickChatCommand): ClearResult {
    val literal = when (command) {
      QuickChatCommand.Clear -> QuickChatCommand.Clear.literal
      QuickChatCommand.Reset -> QuickChatCommand.Reset.literal
      is QuickChatCommand.Question -> throw IllegalArgumentException("Only /clear and /reset are sent as commands")
    }
    val threadId = binding.threadId ?: return ClearResult.NO_THREAD
    val result = try {
      val deadline = clock.nanoTime() + clearDeadline.inWholeNanoseconds
      val sent = withTimeoutOrNull(clearDeadline) {
        sendClear(binding.instanceUrl, literal, threadId, deadline)
      }
      sent ?: ClearResult.TIMED_OUT
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      logger.info("Quick Chat ${command.javaClass.simpleName} failed: ${e.javaClass.name}")
      return ClearResult.FAILED
    }
    logger.info("Quick Chat ${command.javaClass.simpleName} ended: $result")
    return result
  }

  private suspend fun sendClear(instanceUrl: String, literal: String, threadId: String, deadlineNanos: Long): ClearResult {
    val connection: ConnectionSnapshot = runInterruptible { connections.captureIf(instanceUrl) }
      ?: return ClearResult.CONNECTION_CHANGED
    val timeout = RequestBudget(clock, deadlineNanos, requestTimeout).nextTimeout() ?: return ClearResult.TIMED_OUT
    val response = runInterruptible { api.clear(connection, literal, threadId, timeout) }
    if (response.errors.isNotEmpty()) {
      logger.info("Quick Chat clear rejected with ${response.errors.size} error(s)")
      return ClearResult.REJECTED
    }
    return ClearResult.SENT
  }
}
