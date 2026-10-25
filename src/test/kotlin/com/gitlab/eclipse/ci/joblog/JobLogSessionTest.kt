package com.gitlab.eclipse.ci.joblog

import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.TraceFetch
import com.gitlab.eclipse.ci.actions.CONNECTION_CHANGED_MESSAGE
import com.gitlab.eclipse.ci.actions.GENERIC_LOG_ERROR_MESSAGE
import com.gitlab.eclipse.ci.actions.LOG_NOT_FOUND_MESSAGE
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.eclipse.jface.text.Document
import java.io.IOException

private const val LIVE_TEXT = "a\n"
private const val ETAG_1 = "\"1\""

/**
 * [JobLogSession] headless (Tasks 10, 11): the UI-driven loop, the gate, visibility and memory, through
 * [JobLogSessionRegistry.open] with fake platform, GitLab and editors. After every test: no two
 * requests overlapped, no throwable reached the log, and no audit line carried a URL or credential.
 */
class JobLogSessionTest : DescribeSpec({
  lateinit var w: FakeWorld

  beforeEach { w = FakeWorld() }
  afterEach { w.checkInvariants() }

  /** Opens [views] on a running job and lets the first log arrive: LIVE with the next trace due in 3 s. */
  fun live(vararg views: FakeView) {
    w.open(*views)
    w.remote.jobs(job("running", STARTED))
    w.remote.traces(body(LIVE_TEXT, etag = ETAG_1))
    w.env.advance(0)
  }

  describe("the cycle") {
    it("pending, then running, then the log") {
      val view = FakeView()
      w.open(view)
      w.remote.jobs(job("pending"), job("running", STARTED))
      w.remote.traces(body("hello\n", etag = ETAG_1))

      w.env.advance(0)

      view.calls shouldContainExactly listOf("page:Waiting(pending)")
      w.env.pending.single().delayMs shouldBe 5_000

      w.env.advance(5_000)

      w.remote.requests shouldContainExactly listOf("job", "job", "trace etag=null")
      view.calls shouldContainExactly listOf("page:Waiting(pending)", "full", "page:Log")
      w.env.pending.single().delayMs shouldBe 3_000
      w.session.shouldNotBeNull().pollState.phase shouldBe PollPhase.LIVE
    }

    it("a job finished before opening shows its log once and stops") {
      val view = FakeView()
      w.open(view)
      w.remote.jobs(job("success", STARTED))
      w.remote.traces(body("\u001B[32mok\u001B[0m\n"))

      w.env.advance(0)
      w.env.advance(TEN_MINUTES)

      w.remote.requests shouldContainExactly listOf("job", "trace etag=null")
      view.calls shouldContainExactly listOf("full", "page:Log")
      w.env.pending.shouldBeEmpty()
      w.session.shouldNotBeNull().pollState.phase shouldBe PollPhase.STOPPED
    }

    it("a job skipped before it started shows the waiting page and asks nothing more") {
      val view = FakeView()
      w.open(view)
      w.remote.jobs(job("skipped"))

      w.env.advance(0)
      w.env.advance(TEN_MINUTES)

      view.calls shouldContainExactly listOf("page:Waiting(skipped)")
      w.remote.requests shouldContainExactly listOf("job")
    }

    it("an erased log shows the erased page and asks nothing more") {
      val view = FakeView()
      w.open(view)
      w.remote.jobs(job("failed", STARTED, erasedAt = STARTED))

      w.env.advance(0)
      w.env.advance(TEN_MINUTES)

      view.calls shouldContainExactly listOf("page:Erased")
      w.remote.requests shouldContainExactly listOf("job")
    }

    it("B10: a 7.5 s fetch stretches the next interval to 7.5 s") {
      val view = FakeView()
      w.open(view)
      w.remote.jobs(job("running", STARTED))
      w.remote.traces(body(LIVE_TEXT, etag = ETAG_1))
      w.remote.callNanos = 7_500_000_000L

      w.env.advance(0)

      w.env.pending.single().delayMs shouldBe 7_500
    }

    it("a finished log without a final newline gets its last line confirmed when polling stops") {
      val view = FakeView()
      w.open(view)
      w.remote.jobs(job("success", STARTED))
      w.remote.traces(body("a\nb"))

      w.env.advance(0)

      view.calls shouldContainExactly listOf("full", "page:Log", "append:2")
      w.session.shouldNotBeNull().retainedBytes shouldBe 0
    }
  }

  describe("stopping") {
    it("404 on the first job read: unavailable page, one notice, one audit line, nothing after") {
      val view = FakeView()
      w.open(view)
      w.remote.jobReplies += { throw GitLabApiException(404, "", "cid-1") }

      w.env.advance(0)
      w.env.advance(TEN_MINUTES)

      view.calls shouldContainExactly listOf("page:Unavailable($LOG_NOT_FOUND_MESSAGE)")
      w.env.notices shouldContainExactly listOf(LOG_NOT_FOUND_MESSAGE)
      w.errors shouldContainExactly listOf(
        auditLine("displayJobLog", "outcome=http httpStatus=404 correlationId=cid-1"),
      )
      w.remote.requests shouldContainExactly listOf("job")
    }

    it("403 says not found, 401 the generic message") {
      listOf(403 to LOG_NOT_FOUND_MESSAGE, 401 to GENERIC_LOG_ERROR_MESSAGE).forEach { (status, message) ->
        val world = FakeWorld()
        val view = FakeView()
        world.open(view)
        world.remote.jobReplies += { throw GitLabApiException(status, "", null) }

        world.env.advance(0)

        world.env.notices shouldContainExactly listOf(message)
        view.calls shouldContainExactly listOf("page:Unavailable($message)")
        world.errors shouldContainExactly
          listOf(auditLine("displayJobLog", "outcome=http httpStatus=$status"))
        world.checkInvariants()
      }
    }

    it("a changed connection mid-live audits connectionRejected, notifies, keeps the log and asks nothing") {
      val view = FakeView()
      live(view)
      w.remote.pinResult = null

      w.env.advance(3_000)
      w.env.advance(TEN_MINUTES)

      w.remote.requests shouldContainExactly listOf("job", "trace etag=null")
      w.errors shouldContainExactly listOf(auditLine("pollJobLog", "outcome=connectionRejected"))
      w.env.notices shouldContainExactly listOf(CONNECTION_CHANGED_MESSAGE)
      view.calls shouldContainExactly listOf("full", "page:Log")
    }

    it("three transient failures back off 6, 12, 24 s with one error line, then one recovered line") {
      val view = FakeView()
      live(view)
      repeat(3) { w.remote.traceReplies += { throw IOException("reset by $FAKE_URL") } }
      w.remote.traces(TraceFetch.NotModified)
      w.remote.jobs(job("running", STARTED))

      w.env.advance(3_000 + 6_000 + 12_000 + 24_000)

      val delays = w.env.timers.map { it.delayMs }.drop(2)
      delays shouldContainExactly listOf(3_000L, 6_000L, 12_000L, 24_000L, 0L, 3_000L)
      w.errors shouldContainExactly listOf(auditLine("pollJobLog", "outcome=io"))
      w.infos shouldContainExactly listOf(auditLine("pollJobLog", "outcome=recovered"))
      w.env.notices.shouldBeEmpty()
    }

    it("after the last editor goes, the armed timer is cancelled and nothing is asked") {
      val view = FakeView()
      live(view)

      w.registry.detach(FAKE_KEY, view)
      w.env.advance(TEN_MINUTES)

      w.remote.requests shouldContainExactly listOf("job", "trace etag=null")
      w.env.pending.shouldBeEmpty()
    }

    it("after disposeAll nothing is asked") {
      live(FakeView())

      w.registry.disposeAll()
      w.env.advance(TEN_MINUTES)

      w.remote.requests shouldContainExactly listOf("job", "trace etag=null")
      w.session shouldBe null
    }
  }

  describe("late results") {
    it("a result arriving after the last editor closed does nothing at all") {
      val view = FakeView()
      live(view)
      w.remote.traces(body("a\nb\n", etag = "\"2\""))
      w.env.fireNext()

      w.registry.detach(FAKE_KEY, view)
      w.env.drainUi()

      view.calls shouldContainExactly listOf("full", "page:Log")
      w.env.notices.shouldBeEmpty()
      w.errors.shouldBeEmpty()
      w.env.pending.shouldBeEmpty()
    }

    it("a result of the run before Display Log ran again is dropped; only the new run shows") {
      val view = FakeView()
      w.open(view)
      w.remote.jobs(job("pending"), job("skipped"))
      w.env.fireNext()

      w.open(view)
      w.env.drainUi()
      w.env.advance(0)

      view.calls shouldContainExactly listOf("page:Waiting(skipped)")
      w.remote.requests shouldContainExactly listOf("job", "job")
    }

    it("a trace in flight when Display Log ran again is dropped by generation: one timer, the new run's") {
      val view = FakeView()
      live(view)
      w.remote.traces(body("a\nb\n", etag = "\"2\""))
      w.env.fireNext()

      w.open(view)
      w.env.drainUi()

      view.calls shouldContainExactly listOf("full", "page:Log")
      w.env.pending.single().delayMs shouldBe 0

      w.remote.jobs(job("running", STARTED))
      w.remote.traces(body("a\nb\nc\n", etag = "\"3\""))
      w.env.advance(0)

      w.remote.requests shouldContainExactly
        listOf("job", "trace etag=null", "trace etag=$ETAG_1", "job", "trace etag=null")
      w.env.pending.single().delayMs shouldBe 3_000
    }

    it("a result arriving after the plugin stopped is dropped") {
      val view = FakeView()
      live(view)
      w.remote.traces(body("a\nb\n", etag = "\"2\""))
      w.env.fireNext()

      w.env.active = false
      w.env.drainUi()

      view.calls shouldContainExactly listOf("full", "page:Log")
      w.env.pending.shouldBeEmpty()
    }

    it("a disposed editor is skipped while the other one gets the result") {
      val first = FakeView()
      val second = FakeView()
      w.open(first, second)
      w.remote.jobs(job("running", STARTED))
      w.remote.traces(body(LIVE_TEXT))
      w.env.fireNext()
      w.env.drainUi()
      w.env.fireNext()

      first.disposed = true
      w.env.drainUi()

      first.calls.shouldBeEmpty()
      second.calls shouldContainExactly listOf("full", "page:Log")
    }
  }

  describe("visibility") {
    it("hiding the only editor cancels the timer; showing it asks once, right away") {
      val view = FakeView()
      live(view)
      w.remote.traces(TraceFetch.NotModified)

      view.visible = false
      w.registry.visibilityChanged(FAKE_KEY)
      w.env.advance(TEN_MINUTES)

      w.env.pending.shouldBeEmpty()
      w.remote.requests shouldContainExactly listOf("job", "trace etag=null")

      view.visible = true
      w.registry.visibilityChanged(FAKE_KEY)

      w.env.pending.single().delayMs shouldBe 0
      w.env.fireNext(0)
      w.env.drainUi()
      w.remote.requests shouldContainExactly listOf("job", "trace etag=null", "trace etag=$ETAG_1")
    }

    it("a result arriving while hidden is applied, but the next request waits until shown") {
      val view = FakeView()
      live(view)
      w.remote.traces(body("a\nb\n", etag = "\"2\""))
      w.env.fireNext()

      view.visible = false
      w.registry.visibilityChanged(FAKE_KEY)
      w.env.drainUi()

      view.calls.last() shouldStartWith "append"
      w.env.pending.shouldBeEmpty()

      view.visible = true
      w.registry.visibilityChanged(FAKE_KEY)

      w.env.pending.single().delayMs shouldBe 0
    }

    it("polling goes on while one of two editors is still visible") {
      val shown = FakeView()
      val hidden = FakeView()
      live(shown, hidden)

      hidden.visible = false
      w.registry.visibilityChanged(FAKE_KEY)

      w.env.pending.single().delayMs shouldBe 3_000
    }

    it("repeated notices of the same visibility ask only once") {
      val view = FakeView()
      live(view)
      w.remote.traces(TraceFetch.NotModified)

      view.visible = false
      repeat(2) { w.registry.visibilityChanged(FAKE_KEY) }
      view.visible = true
      repeat(2) { w.registry.visibilityChanged(FAKE_KEY) }

      w.env.pending.map { it.delayMs } shouldContainExactly listOf(0L)
      w.env.fireNext(0)
      w.env.drainUi()
      w.remote.requests.count { it.startsWith("trace") } shouldBe 2
    }
  }

  describe("applying (Task 11)") {
    it("two editors on one document: the append changes it once and both take the new runs") {
      val shared = Document()
      val a = FakeView(document = shared)
      val b = FakeView(document = shared)
      live(a, b)
      w.remote.traces(body("a\nb\n", etag = "\"2\""))

      w.env.advance(3_000)

      shared.get() shouldBe "a\nb\n"
      listOf(a, b).forEach { view ->
        view.calls.count { it == "full" } shouldBe 1
        view.calls.count { it.startsWith("append:") } shouldBe 1
      }
    }

    it("an append that does not fit the document audits applyMismatch; the next poll renders it all again") {
      val view = FakeView()
      live(view)
      view.document.set("")
      w.remote.traces(body("a\nb\n", etag = "\"2\""), body("a\nb\nc\n", etag = "\"3\""))

      w.env.advance(3_000)

      view.document.get() shouldBe ""
      w.errors shouldContainExactly listOf(auditLine("pollJobLog", "outcome=applyMismatch"))

      w.env.advance(3_000)

      w.remote.requests shouldContainExactly listOf("job", "trace etag=null", "trace etag=$ETAG_1", "trace etag=null")
      view.document.get() shouldBe "a\nb\nc\n"
      view.calls.last() shouldBe "full"
    }

    it("a final append that does not fit reads the finished log once more from scratch, and only once") {
      val view = FakeView()
      live(view)
      view.document.set("")
      w.remote.jobs(job("success", STARTED))
      w.remote.traces(TraceFetch.NotModified, body("a\nb", etag = "\"2\""), body("a\nb"))

      w.env.advance(3_000)
      w.env.advance(TEN_MINUTES)

      view.document.get() shouldBe "a\nb"
      view.calls.last() shouldBe "append:2"
      w.remote.requests.last() shouldBe "trace etag=null"
      w.remote.requests.count { it == "trace etag=null" } shouldBe 2
      w.remote.traceReplies.shouldBeEmpty()
      w.errors shouldContainExactly listOf(auditLine("pollJobLog", "outcome=applyMismatch"))
      w.session.shouldNotBeNull().pollState.phase shouldBe PollPhase.STOPPED
      w.session.shouldNotBeNull().retainedBytes shouldBe 0
      w.env.pending.shouldBeEmpty()
    }

    it("a live job opens at the end of its log; a job finished before opening stays at the top") {
      val running = FakeView()
      live(running)

      running.reveals shouldBe 1

      val world = FakeWorld()
      val finished = FakeView()
      world.open(finished)
      world.remote.jobs(job("success", STARTED))
      world.remote.traces(body("ok\n"))
      world.env.advance(0)

      finished.calls shouldContainExactly listOf("full", "page:Log")
      finished.reveals shouldBe 0
      world.checkInvariants()
    }
  }

  describe("memory") {
    it("keeps the compare window while live, drops it on stop, and keeps runs for a new duplicate tab") {
      val view = FakeView()
      w.open(view)
      w.remote.jobs(job("running", STARTED), job("success", STARTED))
      w.remote.traces(body("\u001B[32mok\u001B[0m\n", etag = ETAG_1), TraceFetch.NotModified, TraceFetch.NotModified)
      w.env.advance(0)
      val session = w.session.shouldNotBeNull()

      session.retainedBytes shouldBeGreaterThan 0
      session.content.rendered shouldBe RenderResult.EMPTY

      // Unchanged -> status (success) -> final trace -> stop.
      w.env.advance(3_000)

      session.pollState.phase shouldBe PollPhase.STOPPED
      session.retainedBytes shouldBe 0
      val duplicate = FakeView()
      w.registry.attach(FAKE_KEY, duplicate)
      duplicate.calls shouldContainExactly listOf("decorate:1:0")
    }
  }
})
