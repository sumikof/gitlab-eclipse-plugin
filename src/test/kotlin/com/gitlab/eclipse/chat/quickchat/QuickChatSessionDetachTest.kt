package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.navigation.ProjectResolution
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.lang.ref.WeakReference

/**
 * Sends whose background never returns, even when interrupted (design §15.3): the Send comes back on
 * the deadline (A20), abandoned work holds nothing of the UI (A33), and the abandoned count survives
 * a runtime restart (A34). Every stuck call is released in cleanup so its thread dies.
 */
class QuickChatSessionDetachTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val stuckCalls = mutableListOf<StuckCall>()
  val runtimes = mutableListOf<QuickChatRuntime>()

  afterEach {
    stuckCalls.forEach(StuckCall::release)
    stuckCalls.clear()
    runtimes.forEach(QuickChatRuntime::close)
    runtimes.clear()
    awaitNoDetachedJobs() shouldBe true
  }

  fun stuck() = StuckCall().also { stuckCalls += it }

  fun runtime(clock: MonotonicClock) = QuickChatRuntime(clock).also { runtimes += it }

  /** One popup's worth of collaborators around a real [QuickChatService]. */
  class Popup(
    val runtime: QuickChatRuntime,
    val clock: FakeClock,
    connections: QuickChatConnections,
    api: QuickChatApi = mockk(),
    resolve: () -> ProjectResolution = { ProjectResolution.NotInRepository },
  ) {
    val ui = FakeUi()
    val timer = FakeTimer()
    val conversation = QuickChatConversation()
    val service = QuickChatService(
      api,
      connections,
      QuickChatPreflight(api) { resolve() },
      QuickChatPoller(api, connections, clock),
      clock,
    )
  }

  fun session(popup: Popup, view: QuickChatView, editor: Any = Any()) = QuickChatSession(
    popup.conversation,
    popup.runtime,
    popup.service,
    { question -> okContext(question).also { editor.hashCode() } },
    { null },
    view,
    popup.ui.runOnUi,
    popup.timer.schedule,
    popup.clock,
  )

  describe("A20: the deadline returns Send wherever the background is stuck") {
    it("stuck capturing the connection: nothing was sent") {
      val clock = FakeClock()
      val stuck = stuck()
      val popup = Popup(runtime(clock), clock, StuckConnections(stuck))
      val view = RecordingView()
      val s = session(popup, view)
      val t = ticket()
      s.submit(t)
      stuck.awaitEntered() shouldBe true
      clock.advance(QuickChatLimits.ANSWER_DEADLINE)
      popup.timer.fireAll()
      view.released shouldContainExactly listOf(t to false)
      view.bodies().last() shouldBe QuickChatTexts.TIMED_OUT_BEFORE_SEND
      QuickChatDetachedJobs.count shouldBe 1 // abandoned, not waited for
    }

    it("stuck resolving the project") {
      val clock = FakeClock()
      val stuck = stuck()
      val resolve = {
        stuck.block()
        ProjectResolution.NotInRepository
      }
      val popup = Popup(runtime(clock), clock, FakeConnections(), resolve = resolve)
      val view = RecordingView()
      val s = session(popup, view)
      val t = ticket()
      s.submit(t)
      stuck.awaitEntered() shouldBe true
      popup.timer.fireAll()
      view.released shouldContainExactly listOf(t to false)
      view.bodies().last() shouldBe QuickChatTexts.TIMED_OUT_BEFORE_SEND
    }

    it("stuck inside aiAction: the question may have been sent") {
      val clock = FakeClock()
      val stuck = stuck()
      val api = mockk<QuickChatApi>()
      every { api.version(any(), any()) } returns "17.11.0"
      every { api.ask(any(), any(), any(), any(), any(), any(), any()) } answers {
        stuck.block()
        AskResponse("req", emptyList(), "thread")
      }
      val popup = Popup(runtime(clock), clock, FakeConnections(), api)
      val view = RecordingView()
      val s = session(popup, view)
      s.submit(ticket())
      stuck.awaitEntered() shouldBe true
      popup.timer.fireAll()
      view.bodies().last() shouldBe QuickChatTexts.MAYBE_SENT
    }
  }

  describe("A33: abandoned work holds nothing of the popup") {
    /** Strong references a test may keep; none of them may lead back to the session. */
    class Leftovers(
      val session: WeakReference<QuickChatSession>,
      val view: WeakReference<RecordingView>,
      val editor: WeakReference<Any>,
      val job: Job,
      val ui: FakeUi,
      val runtime: QuickChatRuntime,
      val replacement: QuickChatSession?,
    )

    fun abandonStuck(how: String): Leftovers {
      val clock = FakeClock()
      val stuck = stuck()
      val popup = Popup(runtime(clock), clock, StuckConnections(stuck))
      val view = RecordingView()
      val editor = Any()
      val s = session(popup, view, editor)
      s.submit(ticket())
      stuck.awaitEntered() shouldBe true
      val job = popup.conversation.inFlight!!.job!!
      var replacement: QuickChatSession? = null
      when (how) {
        "deadline" -> popup.timer.fireAll()
        "close" -> s.end()
        "replace" -> {
          s.end()
          replacement = session(Popup(popup.runtime, clock, FakeConnections()), RecordingView())
        }
      }
      popup.timer.entries shouldBe emptyList()
      return Leftovers(
        WeakReference(s),
        WeakReference(view),
        WeakReference(editor),
        job,
        popup.ui,
        popup.runtime,
        replacement,
      )
    }

    listOf("deadline", "close", "replace").forEach { how ->
      it("after $how, the stuck job does not reach the session, view or editor; its return shows nothing") {
        val left = abandonStuck(how)
        left.job.isCompleted shouldBe false
        awaitCollected(left.session) shouldBe true
        awaitCollected(left.view) shouldBe true
        awaitCollected(left.editor) shouldBe true

        stuckCalls.forEach { it.release() }
        runBlocking { left.job.join() }
        left.ui.queue.size shouldBe 0
      }
    }

    /** A send whose result (or failure) is queued on the UI, then the popup closes before it runs. */
    fun queuedThenClosed(throwInsteadOfAnswer: Boolean): Leftovers {
      val clock = FakeClock()
      val runtime = runtime(clock)
      val service = mockk<QuickChatService>()
      coEvery { service.ask(any()) } answers {
        if (throwInsteadOfAnswer) error("escaped")
        QuickChatOutcome.Answered("a", BindingUpdate(INSTANCE, PROJECT_PREFLIGHT, "t", false))
      }
      val popup = Popup(runtime, clock, FakeConnections())
      val view = RecordingView()
      val editor = Any()
      val s = QuickChatSession(
        popup.conversation, runtime, service, { q -> okContext(q).also { editor.hashCode() } }, { null },
        view, popup.ui.runOnUi, popup.timer.schedule, clock,
      )
      s.submit(ticket())
      val job = popup.conversation.inFlight!!.job!!
      popup.ui.awaitQueued(1) shouldBe true // deliver / completed has posted …
      runBlocking { job.join() }
      s.end() // … and the popup closes before the UI runs it
      popup.ui.queue.size shouldBe 1
      return Leftovers(WeakReference(s), WeakReference(view), WeakReference(editor), job, popup.ui, runtime, null)
    }

    listOf(false to "deliver", true to "completed").forEach { (throws, via) ->
      it("race: an action queued by $via before the close does not capture the session and does nothing") {
        val left = queuedThenClosed(throws)
        awaitCollected(left.session) shouldBe true
        awaitCollected(left.view) shouldBe true
        left.ui.drain()
        left.ui.queue.size shouldBe 0
      }
    }
  }

  describe("A34: the abandoned count survives a runtime stop and start") {
    it("a new runtime's session is Busy while the old runtime's stuck sends are counted") {
      val clock = FakeClock()
      val first = runtime(clock)
      val stuckOnes = (1..QuickChatLimits.MAX_DETACHED).map {
        val stuck = stuck()
        val popup = Popup(first, clock, StuckConnections(stuck))
        val s = session(popup, RecordingView())
        s.submit(ticket())
        stuck.awaitEntered() shouldBe true
        s.end()
        stuck
      }
      first.close()
      QuickChatDetachedJobs.count shouldBe QuickChatLimits.MAX_DETACHED

      val second = runtime(clock)
      val popup = Popup(second, clock, FakeConnections(current = snapshot()), api = mockk(relaxed = true))
      val view = RecordingView()
      val s = session(popup, view)
      s.submit(ticket())
      view.bodies() shouldContainExactly listOf(QuickChatTexts.BUSY)

      stuckOnes.first().release()
      val until = System.currentTimeMillis() + 5_000
      while (QuickChatDetachedJobs.atLimit() && System.currentTimeMillis() < until) delay(5)
      QuickChatDetachedJobs.count shouldBe QuickChatLimits.MAX_DETACHED - 1
      s.submit(ticket("again"))
      view.bodies().takeLast(2) shouldContainExactly listOf("again", QuickChatTexts.WAITING)
      s.end()
    }
  }
})

/** Polls the GC (bounded) until [ref] is cleared. */
private fun awaitCollected(ref: WeakReference<*>): Boolean {
  repeat(100) {
    System.gc()
    if (ref.get() == null) return true
    @Suppress("UNUSED_VARIABLE")
    val pressure = ByteArray(1 shl 20)
    Thread.sleep(20)
  }
  return ref.get() == null
}
