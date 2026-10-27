package com.gitlab.eclipse.ci.joblog

import com.gitlab.eclipse.api.TraceFetch
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * [JobLogSession]'s Retry state (L4): the status it last saw, a retry in flight and a retry done,
 * through [JobLogSessionRegistry.open] with the fakes of [JobLogSessionFakes].
 */
class JobLogSessionRetryStateTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  lateinit var w: FakeWorld

  beforeEach { w = FakeWorld() }
  afterEach { w.checkInvariants() }

  /** Opens [view] on a job that ended [status] before it started: the waiting page, polling stopped. */
  fun finishedWithoutLog(view: FakeView, status: String): JobLogSession {
    w.open(view)
    w.remote.jobs(job(status))
    w.env.advance(0)
    return w.session.shouldNotBeNull()
  }

  it("remembers the status a poll saw, and offers Retry for a failed job") {
    val session = finishedWithoutLog(FakeView(), "failed")

    session.lastStatus shouldBe "failed"
    session.canRetry shouldBe true
  }

  it("does not offer Retry while the job runs") {
    w.open(FakeView())
    w.remote.jobs(job("running", STARTED))
    w.remote.traces(TraceFetch.NotModified)
    w.env.advance(0)
    val session = w.session.shouldNotBeNull()

    session.lastStatus shouldBe "running"
    session.canRetry shouldBe false
  }

  it("keeps the failed status after the live log stops, although the policy drops it") {
    w.open(FakeView())
    w.remote.jobs(job("running", STARTED), job("failed", STARTED))
    w.remote.traces(body("a\n", etag = "\"1\""), TraceFetch.NotModified, TraceFetch.NotModified)
    w.env.advance(0)
    // Unchanged -> status (failed) -> final trace -> stop.
    w.env.advance(3_000)
    val session = w.session.shouldNotBeNull()

    session.pollState.phase shouldBe PollPhase.STOPPED
    session.pollState.status.shouldBeNull()
    session.lastStatus shouldBe "failed"
    session.canRetry shouldBe true
  }

  it("tells the environment each time the seen status changes, not when it repeats") {
    w.open(FakeView())
    w.remote.jobs(job("pending"), job("pending"), job("failed"))
    w.env.advance(0)
    val afterPending = w.env.retryStateChanges

    w.env.advance(5_000)
    w.env.retryStateChanges shouldBe afterPending

    w.env.advance(5_000)
    w.env.retryStateChanges shouldBe afterPending + 1
    afterPending shouldBe 1
  }

  it("a retry in flight withholds Retry, a failed retry gives it back, a successful one keeps it off") {
    val session = finishedWithoutLog(FakeView(), "canceled")

    session.retryStarted()
    session.canRetry shouldBe false
    session.retryFinished(succeeded = false)
    session.canRetry shouldBe true
    session.retryStarted()
    session.retryFinished(succeeded = true)
    session.canRetry shouldBe false
  }

  it("tells the environment and re-sends the stopped waiting page on every Retry state change") {
    val view = FakeView()
    val session = finishedWithoutLog(view, "canceled")
    val before = w.env.retryStateChanges

    session.retryStarted()
    session.retryFinished(succeeded = false)

    w.env.retryStateChanges shouldBe before + 2
    view.calls shouldContainExactly listOf("page:Waiting(canceled)", "page:Waiting(canceled)", "page:Waiting(canceled)")
  }

  it("re-sends the waiting page when the seen status changes, even when the policy keeps that page") {
    val view = FakeView()
    w.open(view)
    // pending: the waiting page. canceling (live, not started): the policy asks for the trace and
    // leaves the waiting page up, so only the session can tell it that Retry is now possible.
    w.remote.jobs(job("pending"), job("canceling"))
    w.env.advance(0)
    view.calls shouldContainExactly listOf("page:Waiting(pending)")

    w.env.fireNext()
    w.env.drainUi()

    val session = w.session.shouldNotBeNull()
    session.lastStatus shouldBe "canceling"
    session.canRetry shouldBe true
    view.calls shouldContainExactly listOf("page:Waiting(pending)", "page:Waiting(pending)")
  }

  it("reopening the job forgets the seen status but not the retry done") {
    val view = FakeView()
    val session = finishedWithoutLog(view, "failed")
    session.retryStarted()
    session.retryFinished(succeeded = true)

    w.open(view)
    session.lastStatus.shouldBeNull()
    w.remote.jobs(job("failed"))
    w.env.advance(0)

    w.session shouldBe session
    session.lastStatus shouldBe "failed"
    session.canRetry shouldBe false
  }

  it("a disposed session never offers Retry") {
    val view = FakeView()
    val session = finishedWithoutLog(view, "failed")

    w.registry.detach(FAKE_KEY, view)

    session.canRetry shouldBe false
  }
})
