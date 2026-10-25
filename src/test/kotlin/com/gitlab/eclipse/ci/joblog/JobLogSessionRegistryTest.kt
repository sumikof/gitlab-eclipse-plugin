package com.gitlab.eclipse.ci.joblog

import com.gitlab.eclipse.ci.actions.GENERIC_LOG_ERROR_MESSAGE
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation

/**
 * [JobLogSessionRegistry] headless (Task 10): one session per key, alive while an editor is
 * attached, restarted by Display Log, and dropped when opening the editor fails.
 */
class JobLogSessionRegistryTest : DescribeSpec({
  lateinit var w: FakeWorld

  beforeEach { w = FakeWorld() }
  afterEach { w.checkInvariants() }

  fun goLive() {
    w.remote.jobs(job("running", STARTED))
    w.remote.traces(body("a\n", etag = "\"1\""))
    w.env.advance(0)
  }

  describe("reference count") {
    it("polling goes on while one editor is left and the session is disposed with the last one") {
      val a = FakeView()
      val b = FakeView()
      w.open(a, b)
      goLive()
      val session = w.session.shouldNotBeNull()
      w.remote.traces(body("a\nb\n", etag = "\"2\""))

      w.registry.detach(FAKE_KEY, a)
      w.env.advance(3_000)

      w.remote.requests shouldContainExactly listOf("job", "trace etag=null", "trace etag=\"1\"")
      w.session shouldBeSameInstanceAs session

      w.registry.detach(FAKE_KEY, b)

      w.session shouldBe null
      session.content.rendered shouldBe RenderResult.EMPTY
      session.content.page shouldBe JobLogPage.Log
      session.retainedBytes shouldBe 0
      w.env.pending.shouldBeEmpty()
    }

    it("the same editor attached twice counts once") {
      val view = FakeView()
      w.open(view)
      w.registry.attach(FAKE_KEY, view)

      w.registry.detach(FAKE_KEY, view)

      w.session shouldBe null
    }
  }

  describe("open") {
    it("puts the loading page on the content before the editor reads it") {
      val view = FakeView()
      var pageSeen: JobLogPage? = null

      w.registry.open(fakeTarget()) { input ->
        pageSeen = input.content.page
        w.registry.attach(input.key, view)
      }

      pageSeen shouldBe JobLogPage.Loading
      w.env.pending.single().delayMs shouldBe 0
    }

    it("running it again restarts the session on the same content and keeps the log on screen until replaced") {
      val view = FakeView()
      w.open(view)
      goLive()
      val content = w.session.shouldNotBeNull().content
      var pageSeen: JobLogPage? = null

      w.registry.open(fakeTarget()) { input ->
        input.content shouldBeSameInstanceAs content
        pageSeen = input.content.page
        w.registry.attach(input.key, view)
      }
      w.remote.jobs(job("running", STARTED))
      w.remote.traces(body("a\n", etag = "\"1\""))
      w.env.advance(0)

      pageSeen shouldBe JobLogPage.Log
      // The new run renders from scratch (no ETag) and replaces the log; no loading page in between.
      w.remote.requests shouldContainExactly listOf("job", "trace etag=null", "job", "trace etag=null")
      view.calls shouldContainExactly listOf("full", "page:Log", "full")
    }

    it("an editor that fails to open drops the session, audits editorFailed and notifies") {
      w.registry.open(fakeTarget()) { error("cannot open") }

      w.session shouldBe null
      w.errors shouldContainExactly listOf(
        auditLine("displayJobLog", "outcome=editorFailed exceptionClass=IllegalStateException"),
      )
      w.env.notices shouldContainExactly listOf(GENERIC_LOG_ERROR_MESSAGE)
      w.env.advance(TEN_MINUTES)
      w.remote.requests.shouldBeEmpty()
    }

    it("an open that attaches no editor drops the session at once") {
      w.registry.open(fakeTarget()) { }

      w.session shouldBe null
      w.env.pending.shouldBeEmpty()
      w.env.advance(TEN_MINUTES)
      w.remote.requests.shouldBeEmpty()
    }
  }

  // B1 review N-5: a running Save Raw Log does not outlive the job log's editors or the plugin.
  describe("launchSave") {
    fun saveRunning(): Job {
      w.registry.launchSave(FAKE_KEY) { awaitCancellation() }
      return w.env.launched.last()
    }

    it("cancels the download when the last editor closes") {
      val view = FakeView()
      w.open(view)
      val save = saveRunning()

      w.registry.detach(FAKE_KEY, view)

      save.isCancelled shouldBe true
    }

    it("cancels the download when the plugin stops") {
      w.open(FakeView())
      val save = saveRunning()

      w.registry.disposeAll()

      save.isCancelled shouldBe true
    }

    it("keeps the download while another editor of the key is open") {
      val a = FakeView()
      w.open(a, FakeView())
      val save = saveRunning()

      w.registry.detach(FAKE_KEY, a)

      save.isActive shouldBe true
      w.registry.disposeAll()
    }
  }

  describe("disposeAll") {
    it("drops every session and its timers") {
      val view = FakeView()
      w.open(view)

      w.registry.disposeAll()

      w.session shouldBe null
      w.env.pending.shouldBeEmpty()
    }
  }
})
