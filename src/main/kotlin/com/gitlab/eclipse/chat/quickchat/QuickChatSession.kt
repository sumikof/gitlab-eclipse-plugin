package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.chat.quickchat.QuickChatConversation.Entry
import com.gitlab.eclipse.chat.quickchat.QuickChatConversation.InFlight
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.inlinethread.SubmitTicket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Runs one popup's conversation (design §8.1, §9.2): starts each send, watches its deadline, and
 * ends it through the single terminal [finishOnce] (§9.2.4). **UI thread only** — every public method
 * and every action it posts or schedules runs there, so the four ways a send can end need no lock.
 *
 * Nothing here touches SWT or a document: the popup ([view]), the UI hop ([runOnUi], `asyncExec` in
 * PR-2), the UI timer ([scheduleOnUi], `timerExec`), the context capture ([contextSource]) and the
 * Duo availability ([availability]: null when available, else the reason to show) are injected.
 *
 * What goes to the background is only the immutable request and a [ResultSink] (§9.2.5): the lambdas
 * handed to the runtime scope are built by top-level functions so they cannot capture this session.
 * Logs carry outcome kinds and exception class names only (NFR-4).
 *
 * TooManyFunctions is suppressed: every send-ending path (result, deadline, close, replacement) and
 * the streamed partial render must share one UI-thread state and the single [finishOnce]/`abandon`
 * cleanup; splitting them across classes would spread that state and its cleanup order.
 */
@Suppress("TooManyFunctions")
class QuickChatSession(
  private val conversation: QuickChatConversation,
  private val runtime: QuickChatRuntime,
  private val service: QuickChatService,
  private val contextSource: (question: String) -> CapturedContext,
  private val availability: () -> String?,
  private val view: QuickChatView,
  private val runOnUi: (() -> Unit) -> Unit,
  private val scheduleOnUi: (delayMillis: Long, action: () -> Unit) -> Cancellable,
  private val clock: MonotonicClock = runtime.clock,
  private val answerDeadline: Duration = QuickChatLimits.ANSWER_DEADLINE,
  private val maxDetached: Int = QuickChatLimits.MAX_DETACHED,
  private val renderInterval: Duration = QuickChatStreamLimits.RENDER_INTERVAL,
) {
  private val logger by lazy { logger<QuickChatSession>() }

  /** Starts [ticket]'s send (design §9.2 steps 2–8). Every path from here ends in [finishOnce]. */
  fun submit(ticket: SubmitTicket) {
    // Step 2, first statement: time spent capturing or starting counts against the deadline (A31).
    val deadline = clock.nanoTime() + answerDeadline.inWholeNanoseconds
    // The popup's busy state prevents this; should it happen, the old send must still end.
    conversation.inFlight?.let { finishOnce(it.ticket, it.generation, QuickChatOutcome.Interrupted) }
    val send = conversation.begin(ticket, deadline)
    try {
      when (val command = QuickChatCommand.classify(ticket.body)) {
        is QuickChatCommand.Question -> ask(send)
        else -> runCommand(send, command)
      }
    } catch (e: Exception) {
      logger.warn("Quick Chat submit failed: ${e.javaClass.name}")
      finishOnce(ticket, send.generation, QuickChatOutcome.Failed)
    }
  }

  private fun ask(send: InFlight) {
    val ticket = send.ticket
    val gen = send.generation
    // Step 4: end at once, without any network, when the send cannot start.
    availability()?.let { return finishOnce(ticket, gen, QuickChatOutcome.Unavailable(it)) }
    if (QuickChatDetachedJobs.atLimit(maxDetached)) return finishOnce(ticket, gen, QuickChatOutcome.Busy)
    val captured = contextSource(ticket.body)
    val context = when (val result = captured.result) {
      is ContextResult.TooLarge -> return finishOnce(ticket, gen, QuickChatOutcome.TooLarge(result.item))
      is ContextResult.Ok -> result.context
    }
    // Step 5.
    conversation.add(Entry.Question(ticket.body))
    conversation.add(Entry.Pending)
    view.render(conversation.toInlineModel())
    // Steps 6–7: the background gets immutable values, the gate and the sink — nothing else.
    // The bound reference `sink::progress` captures the sink only, never this session (§17).
    val sink = ResultSink(this, ticket, gen, runOnUi)
    send.resultSink = sink
    val request = QuickChatRequest(
      context,
      captured.anchorFile,
      conversation.binding,
      send.deadlineNanos,
      send.gate,
      onProgress = sink::progress,
    )
    val job = launchSend(runtime.scope, service, request, sink)
    send.job = job
    send.completionHandle = watchCompletion(job, sink)
    // Step 8.
    val remaining = send.deadlineNanos - clock.nanoTime()
    if (remaining <= 0) {
      onDeadline(ticket, gen)
    } else {
      send.watchdog = scheduleOnUi(ceilMillis(remaining)) { onDeadline(ticket, gen) }
    }
  }

  /** `/clear` and `/reset` (design §9.4): local effect and ticket release now, the server told later. */
  private fun runCommand(send: InFlight, command: QuickChatCommand) {
    val threaded = conversation.binding?.takeIf { it.threadId != null }
    if (command == QuickChatCommand.Clear) conversation.clearEntries() else conversation.add(Entry.Separator)
    conversation.binding = conversation.binding?.copy(threadId = null)
    finishOnce(send.ticket, send.generation, QuickChatOutcome.Cleared)
    conversation.advanceGeneration()
    if (threaded == null) return
    if (QuickChatDetachedJobs.atLimit(maxDetached)) {
      logger.info("Quick Chat ${command.javaClass.simpleName} not sent: too many abandoned requests")
      return
    }
    // Counted from the start: nobody waits for it, and it may outlive the popup (design §15.4).
    QuickChatDetachedJobs.track(launchClear(runtime.scope, service, threaded, command))
  }

  /** The UI timer's deadline (design §9.2.4 (b)): the gate decides what the user is told (§12.4). */
  fun onDeadline(ticket: SubmitTicket, gen: Long) {
    if (!conversation.isCurrent(ticket, gen)) return
    val send = conversation.inFlight ?: return
    val outcome = when (val state = send.gate.closeIfOpen()) {
      SendGate.State.Open, SendGate.State.Closed -> QuickChatOutcome.TimedOut(beforeSend = true, update = null)
      SendGate.State.Sending -> QuickChatOutcome.MaybeSent(update = null)
      is SendGate.State.Sent -> QuickChatOutcome.TimedOut(beforeSend = false, update = state.update)
    }
    finishOnce(ticket, gen, outcome)
  }

  /**
   * A streamed partial answer (streaming design §9.4 step 2): display only. Dropped unless [ticket]
   * is still the current send; otherwise remembered, and one render is scheduled at a time. The text
   * is read only when that render runs. Should [scheduleOnUi] throw, the exception goes to the
   * posting sink's UI hop: the display is not updated, the send is unaffected.
   *
   * The delay is [renderInterval] at least, and [QuickChatStreamLimits.RENDER_BACKOFF_FACTOR] times
   * the last partial render's duration when that is longer (Codex PR #105 P1). A render is only
   * scheduled once the previous one has run (its timer is cleared as it starts) and the delay counts
   * from this call, after it ended: a render of duration d is followed by a gap of at least
   * factor × d, so partial renders take at most 1 / (1 + factor) of the UI thread.
   */
  fun onProgress(ticket: SubmitTicket, gen: Long, source: () -> String) {
    if (!conversation.isCurrent(ticket, gen)) return
    val send = conversation.inFlight ?: return
    send.progressSource = source
    if (send.renderTimer == null) {
      send.renderTimer = scheduleOnUi(renderDelayMillis(send.lastRenderNanos)) { renderProgress(ticket, gen) }
    }
  }

  /** The next partial render's delay after one that took [lastRenderNanos]; saturates instead of overflowing. */
  private fun renderDelayMillis(lastRenderNanos: Long): Long {
    val factor = QuickChatStreamLimits.RENDER_BACKOFF_FACTOR
    val backoffNanos = lastRenderNanos.coerceIn(0, Long.MAX_VALUE / factor) * factor
    return maxOf(renderInterval.inWholeMilliseconds, ceilMillis(backoffNanos))
  }

  private fun renderProgress(ticket: SubmitTicket, gen: Long) {
    if (!conversation.isCurrent(ticket, gen)) return
    val send = conversation.inFlight ?: return
    send.renderTimer = null
    val source = send.progressSource ?: return
    // Reading the text, building the model and rendering all count: a failed render too.
    val start = clock.nanoTime()
    try {
      guarded("show the partial answer") {
        conversation.showPartial(source())
        view.render(conversation.toInlineModel())
      }
    } finally {
      send.lastRenderNanos = clock.nanoTime() - start
    }
  }

  /**
   * The only terminal of a send (design §9.2.4), called from (a) the delivered result, (b) the
   * deadline, (c) the completion hook and (d) `submit` itself. Only the first call for a send has an
   * effect; the ticket is released exactly once even when applying the outcome or rendering throws.
   */
  fun finishOnce(ticket: SubmitTicket, gen: Long, outcome: QuickChatOutcome) {
    if (!conversation.isCurrent(ticket, gen)) return
    val send = conversation.clearInFlight() ?: return
    try {
      apply(outcome)
    } catch (e: Exception) {
      logger.warn("Quick Chat could not apply ${outcome.javaClass.simpleName}: ${e.javaClass.name}")
    } finally {
      abandon(send)
      val succeeded = outcome is QuickChatOutcome.Answered || outcome is QuickChatOutcome.Cleared
      guarded("release") { view.released(ticket, succeeded) }
      guarded("render") { view.render(conversation.toInlineModel()) }
    }
  }

  /** Stores the outcome in the conversation (design §9.2.4 `apply`); runs only for a current send. */
  private fun apply(outcome: QuickChatOutcome) {
    outcome.update?.let { update ->
      conversation.binding = update.toBinding()
      if (update.projectChanged) conversation.insertSeparatorBeforePendingQuestion()
    }
    when (outcome) {
      is QuickChatOutcome.Answered -> conversation.storeAnswer(outcome.content)
      QuickChatOutcome.Cleared -> Unit
      QuickChatOutcome.ConnectionChanged -> {
        conversation.binding = null
        conversation.advanceGeneration()
        conversation.resolvePending(Entry.Failure(QuickChatTexts.CONNECTION_CHANGED))
        conversation.add(Entry.Separator)
      }
      else -> conversation.resolvePending(Entry.Failure(QuickChatTexts.failure(outcome)))
    }
  }

  /**
   * Closes the conversation (design §9.5 step 2): the same cleanup as [finishOnce] without releasing
   * the ticket (the popup is gone). Later results are dropped by the detached sink or [QuickChatConversation.isCurrent].
   */
  fun end() {
    conversation.advanceGeneration()
    conversation.clearInFlight()?.let(::abandon)
    conversation.binding = null
  }

  /** Lets go of a send's background (design §9.2.4 `finally`): nothing of it may reach this session. */
  private fun abandon(send: InFlight) {
    // The final render is finishOnce's alone: no partial render may follow it (streaming design §9.5).
    guarded("cancel the progress render") { send.renderTimer?.cancel() }
    send.renderTimer = null
    send.progressSource = null
    guarded("cancel the deadline") { send.watchdog?.cancel() }
    send.gate.closeIfOpen()
    send.completionHandle?.dispose()
    send.resultSink?.detach()
    send.job?.let {
      it.cancel()
      QuickChatDetachedJobs.track(it)
    }
  }

  private inline fun guarded(what: String, action: () -> Unit) {
    try {
      action()
    } catch (e: Exception) {
      logger.warn("Quick Chat could not $what: ${e.javaClass.name}")
    }
  }
}

private const val NANOS_PER_MILLI = 1_000_000L

// Divides before rounding up so that no positive value overflows (a saturated render backoff is near Long.MAX_VALUE).
private fun ceilMillis(nanos: Long): Long = nanos / NANOS_PER_MILLI + if (nanos % NANOS_PER_MILLI > 0) 1 else 0

// Top-level on purpose: a lambda built inside the session could capture it (design §9.2.5).
private fun launchSend(
  scope: CoroutineScope,
  service: QuickChatService,
  request: QuickChatRequest,
  sink: ResultSink,
): Job =
  scope.launch { service.ask(request)?.let(sink::deliver) }

private fun watchCompletion(job: Job, sink: ResultSink): DisposableHandle = job.invokeOnCompletion { sink.completed() }

private fun launchClear(
  scope: CoroutineScope,
  service: QuickChatService,
  binding: ConversationBinding,
  command: QuickChatCommand,
): Job =
  scope.launch { service.clear(binding, command) }
