package com.gitlab.eclipse.ci.joblog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import kotlin.random.Random

private data class StepCase(val id: String, val from: PollState, val event: PollEvent, val to: PollState, val out: List<PollCommand>)

private val TRACE = PollRequest.TRACE
private val STATUS = PollRequest.STATUS
private val FINAL_TRACE = PollRequest.FINAL_TRACE
private const val LARGE = HARD_DISPLAY_RAW_BYTES + 1

private fun seen(status: String?, hasTrace: Boolean = false, erased: Boolean = false, elapsed: Long = 0) =
  PollEvent.StatusSeen(status, hasTrace, erased, elapsed)

private fun changed(elapsed: Long = 0, etag: Boolean = true, total: Long = 0) = PollEvent.TraceChanged(
  elapsed,
  etag,
  total
)

private fun unchanged(elapsed: Long = 0, etag: Boolean = true, total: Long = 0) = PollEvent.TraceUnchanged(
  elapsed,
  etag,
  total
)

private fun failed(kind: PollFailureKind) = PollEvent.Failed(kind, 0)

private val live = PollState(phase = PollPhase.LIVE, status = "running", inFlight = TRACE)
private val liveScheduled = PollState(phase = PollPhase.LIVE, status = "running", scheduled = TRACE)
private val liveParked = PollState(phase = PollPhase.LIVE, status = "running", visible = false, parked = TRACE)
private val stopped = PollState(phase = PollPhase.STOPPED)

private fun waiting(
  status: String,
  inFlight: PollRequest? = null,
  scheduled: PollRequest? = null,
  failures: Int = 0,
  expedite: Boolean = false,
) = PollState(
  phase = PollPhase.WAITING,
  status = status,
  inFlight = inFlight,
  scheduled = scheduled,
  failures = failures,
  expedite = expedite,
)

private val STEP_CASES = listOf(
  StepCase(
    "P2 open",
    PollState(),
    PollEvent.Opened(true),
    PollState(scheduled = STATUS),
    listOf(PollCommand.FetchStatus(0))
  ),
  StepCase(
    "P2 open hidden",
    PollState(),
    PollEvent.Opened(false),
    PollState(visible = false, parked = STATUS),
    emptyList()
  ),
  StepCase(
    "P15 pending",
    PollState(inFlight = STATUS),
    seen("pending"),
    waiting("pending", scheduled = STATUS),
    listOf(PollCommand.ShowWaiting("pending"), PollCommand.FetchStatus(5_000)),
  ),
  StepCase(
    "P15 manual 15s",
    PollState(inFlight = STATUS),
    seen("manual"),
    waiting("manual", scheduled = STATUS),
    listOf(PollCommand.ShowWaiting("manual"), PollCommand.FetchStatus(15_000)),
  ),
  StepCase(
    "P13 started while waiting",
    waiting("pending", inFlight = STATUS),
    seen("pending", hasTrace = true),
    PollState(phase = PollPhase.STARTING, status = "pending", scheduled = TRACE),
    listOf(PollCommand.FetchTrace(0)),
  ),
  StepCase(
    "P13 running without trace yet",
    PollState(inFlight = STATUS),
    seen("running"),
    PollState(phase = PollPhase.STARTING, scheduled = TRACE),
    listOf(PollCommand.FetchTrace(0)),
  ),
  StepCase(
    "P12 opened on a finished job",
    PollState(inFlight = STATUS),
    seen("success", hasTrace = true),
    PollState(phase = PollPhase.STARTING, stopAfterStart = true, scheduled = FINAL_TRACE),
    listOf(PollCommand.FinalTrace(0)),
  ),
  StepCase(
    "P14 skipped without a trace",
    PollState(inFlight = STATUS),
    seen("skipped"),
    stopped,
    listOf(PollCommand.ShowWaiting("skipped"), PollCommand.Stop(StopReason.TERMINAL)),
  ),
  StepCase(
    "P11 erased",
    waiting("pending", inFlight = STATUS),
    seen("failed", erased = true),
    stopped,
    listOf(PollCommand.ShowErased, PollCommand.Stop(StopReason.ERASED)),
  ),
  StepCase(
    "P16 finished job shows its log once",
    PollState(phase = PollPhase.STARTING, stopAfterStart = true, inFlight = FINAL_TRACE),
    changed(),
    stopped,
    listOf(PollCommand.ShowLog, PollCommand.Stop(StopReason.TERMINAL)),
  ),
  StepCase(
    "P17 first log",
    PollState(phase = PollPhase.STARTING, status = "running", inFlight = TRACE),
    changed(elapsed = 100),
    liveScheduled,
    listOf(PollCommand.ShowLog, PollCommand.FetchTrace(3_000)),
  ),
  StepCase(
    "P19 first unchanged asks for the status",
    live,
    unchanged(),
    live.copy(inFlight = null, unchangedStreak = 1, pollsSinceStatus = 1, scheduled = STATUS),
    listOf(PollCommand.FetchStatus(0)),
  ),
  StepCase(
    "P19 2nd unchanged goes on with the trace",
    live.copy(unchangedStreak = 1),
    unchanged(),
    live.copy(inFlight = null, unchangedStreak = 2, pollsSinceStatus = 1, scheduled = TRACE),
    listOf(PollCommand.FetchTrace(3_000)),
  ),
  StepCase(
    "P19 11th unchanged asks for the status",
    live.copy(unchangedStreak = 10, pollsSinceStatus = 3),
    unchanged(),
    live.copy(inFlight = null, unchangedStreak = 11, pollsSinceStatus = 4, scheduled = STATUS),
    listOf(PollCommand.FetchStatus(0)),
  ),
  StepCase(
    "P19 10th poll asks for the status",
    live.copy(unchangedStreak = 4, pollsSinceStatus = 9),
    unchanged(),
    live.copy(inFlight = null, unchangedStreak = 5, pollsSinceStatus = 10, scheduled = STATUS),
    listOf(PollCommand.FetchStatus(0)),
  ),
  StepCase(
    "P18 10th poll asks for the status even while the log grows",
    live.copy(pollsSinceStatus = 9),
    changed(),
    live.copy(inFlight = null, pollsSinceStatus = 10, scheduled = STATUS),
    listOf(PollCommand.FetchStatus(0)),
  ),
  StepCase(
    "P18 a changed trace resets the unchanged streak",
    live.copy(unchangedStreak = 5),
    changed(),
    live.copy(inFlight = null, unchangedStreak = 0, pollsSinceStatus = 1, scheduled = TRACE),
    listOf(PollCommand.FetchTrace(3_000)),
  ),
  StepCase(
    "B10 a slow fetch stretches the interval",
    live,
    changed(elapsed = 7_500),
    live.copy(inFlight = null, pollsSinceStatus = 1, scheduled = TRACE),
    listOf(PollCommand.FetchTrace(7_500)),
  ),
  StepCase(
    "B10 capped at 60 s",
    live,
    changed(elapsed = 90_000),
    live.copy(inFlight = null, pollsSinceStatus = 1, scheduled = TRACE),
    listOf(PollCommand.FetchTrace(60_000)),
  ),
  StepCase(
    "R2-3 no etag over the hard limit",
    live,
    changed(etag = false, total = LARGE),
    live.copy(inFlight = null, pollsSinceStatus = 1, largeWithoutEtag = true, scheduled = TRACE),
    listOf(PollCommand.FetchTrace(10_000)),
  ),
  StepCase(
    "R2-3 no etag at exactly the hard limit is not large",
    live,
    changed(etag = false, total = HARD_DISPLAY_RAW_BYTES),
    live.copy(inFlight = null, pollsSinceStatus = 1, scheduled = TRACE),
    listOf(PollCommand.FetchTrace(3_000)),
  ),
  StepCase(
    "R2-3 etag present stays at 3 s",
    live,
    changed(etag = true, total = LARGE),
    live.copy(inFlight = null, pollsSinceStatus = 1, scheduled = TRACE),
    listOf(PollCommand.FetchTrace(3_000)),
  ),
  StepCase(
    "P20 erased while live",
    live.copy(inFlight = STATUS),
    seen("running", erased = true),
    stopped,
    listOf(PollCommand.ShowErased, PollCommand.Stop(StopReason.ERASED)),
  ),
  StepCase(
    "P21 terminal status asks for the final trace",
    live.copy(inFlight = STATUS),
    seen("success", hasTrace = true),
    PollState(phase = PollPhase.FINAL, status = "running", scheduled = FINAL_TRACE),
    listOf(PollCommand.FinalTrace(0)),
  ),
  StepCase(
    "P22 still running keeps polling the trace",
    live.copy(inFlight = STATUS, unchangedStreak = 3, pollsSinceStatus = 4),
    seen("running", hasTrace = true),
    live.copy(inFlight = null, unchangedStreak = 3, pollsSinceStatus = 0, scheduled = TRACE),
    listOf(PollCommand.FetchTrace(3_000)),
  ),
  StepCase(
    "P22 a changed status is remembered (running to canceling)",
    live.copy(inFlight = STATUS),
    seen("canceling", hasTrace = true),
    live.copy(status = "canceling", inFlight = null, pollsSinceStatus = 0, scheduled = TRACE),
    listOf(PollCommand.FetchTrace(3_000)),
  ),
  StepCase(
    "P26 a status result while STARTING changes nothing but the in-flight mark",
    PollState(phase = PollPhase.STARTING, status = "running", inFlight = STATUS),
    seen("success", hasTrace = true),
    PollState(phase = PollPhase.STARTING, status = "running"),
    emptyList(),
  ),
  StepCase(
    "P26 a status result while STARTING after failures still reports the recovery",
    PollState(phase = PollPhase.STARTING, status = "running", inFlight = STATUS, failures = 2),
    seen("running"),
    PollState(phase = PollPhase.STARTING, status = "running"),
    listOf(PollCommand.ReportRecovered),
  ),
  StepCase(
    "P23 final trace ends the session",
    PollState(phase = PollPhase.FINAL, status = "running", inFlight = FINAL_TRACE),
    changed(),
    stopped,
    listOf(PollCommand.Stop(StopReason.TERMINAL)),
  ),
  StepCase(
    "P24 first failure is reported",
    live,
    failed(PollFailureKind.TRANSIENT),
    live.copy(inFlight = null, failures = 1, scheduled = TRACE),
    listOf(PollCommand.ReportFailure(PollFailureKind.TRANSIENT), PollCommand.FetchTrace(6_000)),
  ),
  StepCase(
    "P24 third failure is not reported again",
    live.copy(failures = 2),
    failed(PollFailureKind.TRANSIENT),
    live.copy(inFlight = null, failures = 3, scheduled = TRACE),
    listOf(PollCommand.FetchTrace(24_000)),
  ),
  StepCase(
    "P24 capped at 60 s",
    live.copy(failures = 6),
    failed(PollFailureKind.TRANSIENT),
    live.copy(inFlight = null, failures = 7, scheduled = TRACE),
    listOf(PollCommand.FetchTrace(60_000)),
  ),
  StepCase(
    "recovery is reported once",
    live.copy(failures = 3),
    changed(),
    live.copy(inFlight = null, pollsSinceStatus = 1, scheduled = TRACE),
    listOf(PollCommand.ReportRecovered, PollCommand.FetchTrace(3_000)),
  ),
  StepCase(
    "P24 waiting backs off from the wait interval",
    waiting("manual", inFlight = STATUS),
    failed(PollFailureKind.TRANSIENT),
    waiting("manual", failures = 1, scheduled = STATUS),
    listOf(PollCommand.ReportFailure(PollFailureKind.TRANSIENT), PollCommand.FetchStatus(30_000)),
  ),
  StepCase(
    "P25 not found",
    live,
    failed(PollFailureKind.NOT_FOUND),
    stopped,
    listOf(PollCommand.Stop(StopReason.NOT_FOUND))
  ),
  StepCase("P25 denied", live, failed(PollFailureKind.DENIED), stopped, listOf(PollCommand.Stop(StopReason.DENIED))),
  StepCase(
    "P25 unpinned",
    live,
    failed(PollFailureKind.UNPINNED),
    stopped,
    listOf(PollCommand.Stop(StopReason.UNPINNED))
  ),
  StepCase(
    "P25 keeps the hidden flag",
    liveParked,
    failed(PollFailureKind.DENIED),
    stopped.copy(visible = false),
    listOf(PollCommand.Stop(StopReason.DENIED)),
  ),
  StepCase("P3 fired", liveScheduled, PollEvent.Fired, live, emptyList()),
  StepCase("P3 fired without a timer", live, PollEvent.Fired, live, emptyList()),
  StepCase(
    "P4 hide while scheduled parks the request",
    liveScheduled,
    PollEvent.Hidden,
    liveParked,
    listOf(PollCommand.CancelScheduled),
  ),
  StepCase("P4 hide while in flight", live, PollEvent.Hidden, live.copy(visible = false), emptyList()),
  StepCase(
    "hidden result parks the next request",
    live.copy(visible = false),
    changed(),
    live.copy(visible = false, inFlight = null, pollsSinceStatus = 1, parked = TRACE),
    emptyList(),
  ),
  StepCase(
    "P5 visible fetches at once",
    liveParked,
    PollEvent.Visible,
    liveScheduled,
    listOf(PollCommand.FetchTrace(0))
  ),
  StepCase("P6 visible twice", liveScheduled, PollEvent.Visible, liveScheduled, emptyList()),
  StepCase("P6 hidden twice", liveParked, PollEvent.Hidden, liveParked, emptyList()),
  StepCase(
    "P7 action done while waiting",
    waiting("manual", scheduled = STATUS),
    PollEvent.ActionDone,
    waiting("manual", scheduled = STATUS),
    listOf(PollCommand.CancelScheduled, PollCommand.FetchStatus(0)),
  ),
  StepCase(
    "P8 action done while a check is in flight",
    waiting("manual", inFlight = STATUS),
    PollEvent.ActionDone,
    waiting("manual", inFlight = STATUS, expedite = true),
    emptyList(),
  ),
  StepCase(
    "P8 the expedited result is checked again at once",
    waiting("manual", inFlight = STATUS, expedite = true),
    seen("manual"),
    waiting("manual", scheduled = STATUS),
    listOf(PollCommand.ShowWaiting("manual"), PollCommand.FetchStatus(0)),
  ),
  StepCase(
    "P9 action done while parked",
    waiting("manual").copy(visible = false, parked = STATUS),
    PollEvent.ActionDone,
    waiting("manual").copy(visible = false, parked = STATUS),
    emptyList(),
  ),
  StepCase("P10 action done while live", liveScheduled, PollEvent.ActionDone, liveScheduled, emptyList()),
  StepCase(
    "P1 closed cancels the timer",
    liveScheduled,
    PollEvent.Closed,
    stopped,
    listOf(PollCommand.CancelScheduled, PollCommand.Stop(StopReason.CLOSED)),
  ),
  StepCase("P1 closed while in flight", live, PollEvent.Closed, stopped, listOf(PollCommand.Stop(StopReason.CLOSED))),
  StepCase("P0 nothing after stop", stopped, changed(), stopped, emptyList()),
  StepCase(
    "P0 visible after stop",
    stopped.copy(visible = false),
    PollEvent.Visible,
    stopped.copy(visible = false),
    emptyList()
  ),
)

private val EVENTS: List<PollEvent> = listOf(
  PollEvent.Opened(true),
  PollEvent.Opened(false),
  PollEvent.Fired,
  seen("pending"),
  seen("manual"),
  seen("running", hasTrace = true),
  seen("success", hasTrace = true),
  seen("failed", erased = true),
  seen("skipped"),
  changed(elapsed = 100),
  changed(elapsed = 80_000, etag = false, total = LARGE),
  unchanged(elapsed = 100),
  failed(PollFailureKind.TRANSIENT),
  failed(PollFailureKind.TRANSIENT),
  failed(PollFailureKind.NOT_FOUND),
  failed(PollFailureKind.DENIED),
  failed(PollFailureKind.UNPINNED),
  PollEvent.Hidden,
  PollEvent.Visible,
  PollEvent.ActionDone,
  PollEvent.Closed,
)

private fun delayOf(command: PollCommand): Long? = when (command) {
  is PollCommand.FetchTrace -> command.delayMs
  is PollCommand.FetchStatus -> command.delayMs
  is PollCommand.FinalTrace -> command.delayMs
  else -> null
}

class JobLogPollPolicyTest : DescribeSpec({
  describe("step") {
    withData(nameFn = { it.id }, STEP_CASES) { case ->
      val (state, commands) = JobLogPollPolicy.step(case.from, case.event)
      state shouldBe case.to
      commands shouldBe case.out
    }
  }

  describe("properties over random event sequences (fixed seed)") {
    it("never schedules while in flight, never fetches while hidden, stays stopped, keeps delays in range") {
      val random = Random(20261025)
      repeat(500) {
        var state = PollState()
        repeat(random.nextInt(1, 61)) {
          val event = EVENTS[random.nextInt(EVENTS.size)]
          val wasStopped = state.phase == PollPhase.STOPPED
          val (next, commands) = JobLogPollPolicy.step(state, event)

          (next.scheduled != null && next.inFlight != null) shouldBe false
          if (!next.visible) commands.none { c -> delayOf(c) != null } shouldBe true
          if (wasStopped) {
            next shouldBe state
            commands shouldBe emptyList()
          }
          commands.mapNotNull { c -> delayOf(c) }.all { d -> d in 0..JobLogPollPolicy.MAX_DELAY_MS } shouldBe true
          state = next
        }
      }
    }
  }
})
