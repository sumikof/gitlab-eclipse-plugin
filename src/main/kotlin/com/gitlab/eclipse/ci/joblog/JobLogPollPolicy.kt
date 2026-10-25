package com.gitlab.eclipse.ci.joblog

enum class PollPhase { LOADING, WAITING, STARTING, LIVE, FINAL, STOPPED }

enum class PollRequest { TRACE, STATUS, FINAL_TRACE }

enum class PollFailureKind { TRANSIENT, NOT_FOUND, DENIED, UNPINNED }

enum class StopReason { TERMINAL, ERASED, NOT_FOUND, DENIED, UNPINNED, CLOSED }

/** Everything the polling plan remembers between events. */
data class PollState(
  val phase: PollPhase = PollPhase.LOADING,
  val status: String? = null,
  /** STARTING ends in Stop(TERMINAL): the job had already finished when the log was opened. */
  val stopAfterStart: Boolean = false,
  val visible: Boolean = true,
  /** A timer is armed and has not fired. */
  val scheduled: PollRequest? = null,
  /** The request fired and its result is not applied yet. */
  val inFlight: PollRequest? = null,
  /** A request withheld while the editor is hidden. */
  val parked: PollRequest? = null,
  /** The next emitted request uses delay 0 (a button was pressed while a request was in flight). */
  val expedite: Boolean = false,
  /** Consecutive transient failures. */
  val failures: Int = 0,
  val unchangedStreak: Int = 0,
  val pollsSinceStatus: Int = 0,
  val largeWithoutEtag: Boolean = false,
)

sealed interface PollEvent {
  data class Opened(val visible: Boolean) : PollEvent

  data object Fired : PollEvent

  data class StatusSeen(
    val status: String?,
    val hasTrace: Boolean,
    val erased: Boolean,
    val elapsedMs: Long,
  ) : PollEvent

  data class TraceChanged(val elapsedMs: Long, val etagPresent: Boolean, val totalBytes: Long) : PollEvent

  data class TraceUnchanged(val elapsedMs: Long, val etagPresent: Boolean, val totalBytes: Long) : PollEvent

  data class Failed(val kind: PollFailureKind, val elapsedMs: Long) : PollEvent

  data object Hidden : PollEvent

  data object Visible : PollEvent

  data object ActionDone : PollEvent

  data object Closed : PollEvent
}

sealed interface PollCommand {
  data class FetchTrace(val delayMs: Long) : PollCommand

  data class FetchStatus(val delayMs: Long) : PollCommand

  data class FinalTrace(val delayMs: Long) : PollCommand

  data object CancelScheduled : PollCommand

  /** A not-yet-started job, or a finished job that has no log to show. */
  data class ShowWaiting(val status: String?) : PollCommand

  data object ShowLog : PollCommand

  data object ShowErased : PollCommand

  data class ReportFailure(val kind: PollFailureKind) : PollCommand

  data object ReportRecovered : PollCommand

  data class Stop(val reason: StopReason) : PollCommand
}

private typealias Step = Pair<PollState, List<PollCommand>>

private const val MAX_BACKOFF_SHIFT = 20

/** The polling plan as a pure function: (state, event) -> (state, commands). Runs on any thread, no I/O. */
object JobLogPollPolicy {
  const val LIVE_INTERVAL_MS = 3_000L
  const val LIVE_INTERVAL_LARGE_NO_ETAG_MS = 10_000L
  const val WAIT_PENDING_INTERVAL_MS = 5_000L
  const val WAIT_MANUAL_INTERVAL_MS = 15_000L
  const val MAX_DELAY_MS = 60_000L
  const val STATUS_EVERY_UNCHANGED = 10
  const val STATUS_EVERY_POLLS = 10

  fun step(state: PollState, event: PollEvent): Pair<PollState, List<PollCommand>> {
    if (state.phase == PollPhase.STOPPED) return state to emptyList()
    return when (event) {
      PollEvent.Closed -> PollEvents.close(state)
      is PollEvent.Opened -> PollEvents.open(state, event)
      PollEvent.Fired -> PollEvents.fire(state)
      PollEvent.Hidden -> PollEvents.hide(state)
      PollEvent.Visible -> PollEvents.show(state)
      PollEvent.ActionDone -> PollEvents.actionDone(state)
      is PollEvent.Failed -> PollEvents.failed(state, event)
      is PollEvent.StatusSeen -> result(state) { s, recovered -> PollResults.statusSeen(s, event, recovered) }
      is PollEvent.TraceChanged -> traceEvent(state, event.etagPresent, event.totalBytes, event.elapsedMs, true)
      is PollEvent.TraceUnchanged -> traceEvent(state, event.etagPresent, event.totalBytes, event.elapsedMs, false)
    }
  }
}

private fun traceEvent(state: PollState, etagPresent: Boolean, totalBytes: Long, elapsedMs: Long, changed: Boolean) =
  result(state) { s, recovered ->
    val large = !etagPresent && totalBytes > HARD_DISPLAY_RAW_BYTES
    PollResults.traceResult(s.copy(largeWithoutEtag = large), elapsedMs, changed, recovered)
  }

private fun liveBase(s: PollState) =
  if (s.largeWithoutEtag) JobLogPollPolicy.LIVE_INTERVAL_LARGE_NO_ETAG_MS else JobLogPollPolicy.LIVE_INTERVAL_MS

private fun liveDelay(s: PollState, elapsedMs: Long) =
  minOf(maxOf(liveBase(s), elapsedMs), JobLogPollPolicy.MAX_DELAY_MS)

private fun backoff(base: Long, failures: Int): Long =
  minOf(base shl minOf(failures, MAX_BACKOFF_SHIFT), JobLogPollPolicy.MAX_DELAY_MS)

private fun command(request: PollRequest, delayMs: Long): PollCommand = when (request) {
  PollRequest.TRACE -> PollCommand.FetchTrace(delayMs)
  PollRequest.STATUS -> PollCommand.FetchStatus(delayMs)
  PollRequest.FINAL_TRACE -> PollCommand.FinalTrace(delayMs)
}

/** Schedules [request] after [delayMs] when visible, otherwise parks it (nothing is armed while hidden). */
private fun emit(s: PollState, request: PollRequest, delayMs: Long): Step {
  val delay = if (s.expedite) 0L else delayMs
  val base = s.copy(expedite = false)
  return if (s.visible) {
    base.copy(scheduled = request) to listOf(command(request, delay))
  } else {
    base.copy(parked = request) to emptyList()
  }
}

private fun stopped(s: PollState, vararg commands: PollCommand): Step =
  PollState(phase = PollPhase.STOPPED, visible = s.visible) to commands.toList()

private fun stoppedWith(s: PollState, before: List<PollCommand>, vararg commands: PollCommand): Step =
  PollState(phase = PollPhase.STOPPED, visible = s.visible) to (before + commands)

/** Applies a result event: clears the in-flight mark and reports a recovery after transient failures. */
private fun result(s: PollState, handle: (PollState, List<PollCommand>) -> Step): Step {
  val recovered: List<PollCommand> = if (s.failures > 0) listOf(PollCommand.ReportRecovered) else emptyList()
  val (next, commands) = handle(s.copy(inFlight = null, failures = 0), recovered)
  return next to commands
}

/** Puts the commands that must come first (a recovery report) before this step's commands. */
private fun Step.after(first: List<PollCommand>): Step = this.first to (first + this.second)

private object PollEvents {
  fun close(s: PollState): Step =
    if (s.scheduled != null) {
      stopped(s, PollCommand.CancelScheduled, PollCommand.Stop(StopReason.CLOSED))
    } else {
      stopped(s, PollCommand.Stop(StopReason.CLOSED))
    }

  fun open(s: PollState, event: PollEvent.Opened): Step {
    val (next, commands) = emit(PollState(visible = event.visible), PollRequest.STATUS, 0)
    return if (s.scheduled != null) {
      next to (listOf<PollCommand>(PollCommand.CancelScheduled) + commands)
    } else {
      next to commands
    }
  }

  fun fire(s: PollState): Step =
    if (s.scheduled == null) s to emptyList() else s.copy(inFlight = s.scheduled, scheduled = null) to emptyList()

  fun hide(s: PollState): Step {
    if (!s.visible) return s to emptyList()
    val hidden = s.copy(visible = false)
    return if (s.scheduled != null) {
      hidden.copy(scheduled = null, parked = s.scheduled) to listOf(PollCommand.CancelScheduled)
    } else {
      hidden to emptyList()
    }
  }

  fun show(s: PollState): Step {
    if (s.visible) return s to emptyList()
    val shown = s.copy(visible = true)
    val parked = s.parked ?: return shown to emptyList()
    return emit(shown.copy(parked = null), parked, 0)
  }

  fun actionDone(s: PollState): Step {
    if (s.phase != PollPhase.WAITING) return s to emptyList()
    return when {
      s.scheduled != null -> {
        val (next, commands) = emit(s.copy(scheduled = null), PollRequest.STATUS, 0)
        next to (listOf<PollCommand>(PollCommand.CancelScheduled) + commands)
      }
      s.inFlight != null -> s.copy(expedite = true) to emptyList()
      s.parked != null -> s.copy(parked = PollRequest.STATUS) to emptyList()
      else -> s to emptyList()
    }
  }

  fun failed(s: PollState, event: PollEvent.Failed): Step = when (event.kind) {
    PollFailureKind.NOT_FOUND -> stopped(s, PollCommand.Stop(StopReason.NOT_FOUND))
    PollFailureKind.DENIED -> stopped(s, PollCommand.Stop(StopReason.DENIED))
    PollFailureKind.UNPINNED -> stopped(s, PollCommand.Stop(StopReason.UNPINNED))
    PollFailureKind.TRANSIENT -> transientFailure(s)
  }

  fun transientFailure(s: PollState): Step {
    val request = s.inFlight ?: return s to emptyList()
    val failures = s.failures + 1
    val waiting = request == PollRequest.STATUS && s.phase == PollPhase.WAITING
    val base = if (waiting) waitIntervalMs(s.status) else liveBase(s)
    val (next, commands) = emit(s.copy(inFlight = null, failures = failures), request, backoff(base, failures))
    val report: List<PollCommand> =
      if (failures == 1) listOf(PollCommand.ReportFailure(PollFailureKind.TRANSIENT)) else emptyList()
    return next to (report + commands)
  }
}

private object PollResults {
  fun statusSeen(s: PollState, event: PollEvent.StatusSeen, recovered: List<PollCommand>): Step {
    val kind = classifyJobStatus(event.status)
    return when (s.phase) {
      PollPhase.LOADING, PollPhase.WAITING -> beforeStart(s, event, kind, recovered)
      PollPhase.LIVE -> liveStatus(s, event, kind, recovered)
      else -> s to recovered
    }
  }

  fun beforeStart(s: PollState, event: PollEvent.StatusSeen, kind: JobStatusClass, recovered: List<PollCommand>): Step {
    val starting = s.copy(phase = PollPhase.STARTING)
    return when {
      event.erased -> stoppedWith(s, recovered, PollCommand.ShowErased, PollCommand.Stop(StopReason.ERASED))
      event.hasTrace && kind == JobStatusClass.TERMINAL ->
        emit(starting.copy(stopAfterStart = true), PollRequest.FINAL_TRACE, 0).after(recovered)
      event.hasTrace || kind == JobStatusClass.LIVE ->
        emit(starting.copy(stopAfterStart = false), PollRequest.TRACE, 0).after(recovered)
      kind == JobStatusClass.TERMINAL ->
        stoppedWith(s, recovered, PollCommand.ShowWaiting(event.status), PollCommand.Stop(StopReason.TERMINAL))
      else -> {
        val waiting = s.copy(phase = PollPhase.WAITING, status = event.status)
        val (next, commands) = emit(waiting, PollRequest.STATUS, waitIntervalMs(event.status))
        next to (recovered + PollCommand.ShowWaiting(event.status) + commands)
      }
    }
  }

  fun liveStatus(s: PollState, event: PollEvent.StatusSeen, kind: JobStatusClass, recovered: List<PollCommand>): Step =
    when {
      event.erased -> stoppedWith(s, recovered, PollCommand.ShowErased, PollCommand.Stop(StopReason.ERASED))
      kind == JobStatusClass.TERMINAL ->
        emit(s.copy(phase = PollPhase.FINAL, pollsSinceStatus = 0), PollRequest.FINAL_TRACE, 0).after(recovered)
      else ->
        emit(s.copy(status = event.status, pollsSinceStatus = 0), PollRequest.TRACE, liveDelay(s, event.elapsedMs))
          .after(recovered)
    }

  fun traceResult(s: PollState, elapsedMs: Long, changed: Boolean, recovered: List<PollCommand>): Step =
    when (s.phase) {
      PollPhase.STARTING ->
        if (s.stopAfterStart) {
          stoppedWith(s, recovered, PollCommand.ShowLog, PollCommand.Stop(StopReason.TERMINAL))
        } else {
          val live = s.copy(phase = PollPhase.LIVE, unchangedStreak = 0, pollsSinceStatus = 0)
          val (next, commands) = emit(live, PollRequest.TRACE, liveDelay(live, elapsedMs))
          next to (recovered + PollCommand.ShowLog + commands)
        }
      PollPhase.LIVE -> liveTrace(s, elapsedMs, changed).after(recovered)
      PollPhase.FINAL -> stoppedWith(s, recovered, PollCommand.Stop(StopReason.TERMINAL))
      else -> s to recovered
    }

  fun liveTrace(s: PollState, elapsedMs: Long, changed: Boolean): Step {
    val streak = if (changed) 0 else s.unchangedStreak + 1
    val polls = s.pollsSinceStatus + 1
    val next = s.copy(unchangedStreak = streak, pollsSinceStatus = polls)
    val unchangedDue = !changed && (streak == 1 || (streak - 1) % JobLogPollPolicy.STATUS_EVERY_UNCHANGED == 0)
    val statusDue = polls >= JobLogPollPolicy.STATUS_EVERY_POLLS || unchangedDue
    return if (statusDue) {
      emit(next, PollRequest.STATUS, 0)
    } else {
      emit(next, PollRequest.TRACE, liveDelay(next, elapsedMs))
    }
  }
}
