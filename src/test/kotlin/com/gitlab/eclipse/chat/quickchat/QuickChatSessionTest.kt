package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.util.Collections
import kotlin.time.Duration.Companion.seconds

private const val THREAD = "gid://gitlab/Ai::Conversation::Thread/7"

class QuickChatSessionTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val update = BindingUpdate(INSTANCE, PROJECT_PREFLIGHT, THREAD, projectChanged = false)
  val cleanups = mutableListOf<() -> Unit>()

  class Harness(
    var availability: () -> String? = { null },
    runtime: QuickChatRuntime? = null,
  ) {
    val clock = FakeClock(1_000)
    val ui = FakeUi()
    val timer = FakeTimer()
    val view = RecordingView()
    val conversation = QuickChatConversation()
    val runtime = runtime ?: QuickChatRuntime(clock)
    val service = mockk<QuickChatService>()
    val answers = Channel<QuickChatOutcome?>(Channel.UNLIMITED)
    val requests: MutableList<QuickChatRequest> = Collections.synchronizedList(mutableListOf())
    val clears: MutableList<ConversationBinding> = Collections.synchronizedList(mutableListOf())
    var clearBlock: () -> Unit = {}
    var contextSource: (String) -> CapturedContext = { okContext(it) }
    val session = QuickChatSession(
      conversation,
      this.runtime,
      service,
      { contextSource(it) },
      { availability() },
      view,
      ui.runOnUi,
      timer.schedule,
      clock,
    )

    init {
      coEvery { service.ask(any()) } coAnswers {
        requests += firstArg<QuickChatRequest>()
        answers.receive()
      }
      coEvery { service.clear(any(), any()) } coAnswers {
        clears += firstArg<ConversationBinding>()
        runInterruptible { clearBlock() }
        ClearResult.SENT
      }
    }

    fun awaitRequest(): QuickChatRequest {
      val until = System.currentTimeMillis() + 5_000
      while (requests.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(5)
      return requests.single()
    }
  }

  fun harness(availability: () -> String? = { null }, runtime: QuickChatRuntime? = null) =
    Harness(availability, runtime).also { h -> cleanups += { h.runtime.close() } }

  afterEach {
    cleanups.forEach { it() }
    cleanups.clear()
    awaitNoDetachedJobs() shouldBe true
  }

  describe("a question that is answered") {
    it("shows the question and the waiting entry, then the answer, and releases as succeeded") {
      val h = harness()
      val t = ticket("Explain SECRET")
      h.session.submit(t)
      h.view.bodies() shouldContainExactly listOf("Explain SECRET", QuickChatTexts.WAITING)
      h.view.released shouldBe emptyList()
      h.timer.entries.single().delayMillis shouldBe QuickChatLimits.ANSWER_DEADLINE.inWholeMilliseconds

      h.answers.send(QuickChatOutcome.Answered("The answer", update))
      h.ui.runNext() shouldBe true
      h.view.bodies() shouldContainExactly listOf("Explain SECRET", "The answer")
      h.view.released shouldContainExactly listOf(t to true)
      h.conversation.binding shouldBe update.toBinding()
      h.conversation.inFlight.shouldBeNull()
      h.timer.entries shouldBe emptyList()
      h.awaitRequest().binding.shouldBeNull()
    }

    it("sends the stored binding with the next question") {
      val h = harness()
      h.session.submit(ticket("one"))
      h.answers.send(QuickChatOutcome.Answered("a", update))
      h.ui.runNext() shouldBe true
      h.requests.clear()
      h.session.submit(ticket("two"))
      h.awaitRequest().binding shouldBe update.toBinding()
      h.answers.send(QuickChatOutcome.Answered("b", update))
      h.ui.runNext() shouldBe true
    }
  }

  describe("a question that fails") {
    it("replaces the waiting entry with the §14 text and keeps the draft (released not succeeded)") {
      val h = harness()
      val t = ticket()
      h.session.submit(t)
      h.answers.send(QuickChatOutcome.TransportFailed(TransportKind.HTTP, 500, "cid-1", null))
      h.ui.runNext() shouldBe true
      val shown = h.view.bodies()
      shown.size shouldBe 2
      shown[1] shouldContain QuickChatTexts.SEND_FAILED
      shown[1] shouldContain "500"
      shown[1] shouldContain "cid-1"
      h.view.released shouldContainExactly listOf(t to false)
    }

    it("a failed send still stores its update; a changed project puts New chat before its question (A26)") {
      val h = harness()
      h.conversation.binding = ConversationBinding(INSTANCE, PROJECT_PREFLIGHT, "old-thread")
      h.conversation.add(QuickChatConversation.Entry.Question("earlier"))
      h.conversation.add(QuickChatConversation.Entry.Answer("earlier answer"))
      val changed = update.copy(threadId = null, projectChanged = true)
      h.session.submit(ticket("new q"))
      h.answers.send(QuickChatOutcome.ServerRejected(listOf("no"), changed))
      h.ui.runNext() shouldBe true
      h.conversation.binding shouldBe changed.toBinding()
      h.view.bodies() shouldContainExactly listOf("earlier", "earlier answer", QuickChatTexts.NEW_CHAT, "new q", "no")
    }

    it("a rejection without messages shows the fallback text") {
      val h = harness()
      h.session.submit(ticket())
      h.answers.send(QuickChatOutcome.ServerRejected(emptyList(), null))
      h.ui.runNext() shouldBe true
      h.view.bodies()[1] shouldBe QuickChatTexts.SERVER_REJECTED_FALLBACK
    }

    it("ConnectionChanged drops the whole binding and adds New chat after the explanation") {
      val h = harness()
      h.conversation.binding = update.toBinding()
      h.session.submit(ticket("q"))
      h.answers.send(QuickChatOutcome.ConnectionChanged)
      h.ui.runNext() shouldBe true
      h.conversation.binding.shouldBeNull()
      h.view.bodies() shouldContainExactly listOf("q", QuickChatTexts.CONNECTION_CHANGED, QuickChatTexts.NEW_CHAT)
    }
  }

  describe("immediate failures (d): nothing is started") {
    it("unavailable shows the reason") {
      val h = harness(availability = { "Duo is off" })
      val t = ticket()
      h.session.submit(t)
      h.view.bodies() shouldContainExactly listOf("Duo is off")
      h.view.released shouldContainExactly listOf(t to false)
      h.timer.entries shouldBe emptyList()
      coVerify(exactly = 0) { h.service.ask(any()) }
    }

    it("a too-large selection or question shows the §9.2.1 text") {
      val h = harness()
      h.contextSource = { CapturedContext(ContextResult.TooLarge(TooLargeItem.SELECTION), null) }
      h.session.submit(ticket())
      h.contextSource = { CapturedContext(ContextResult.TooLarge(TooLargeItem.QUESTION), null) }
      h.session.submit(ticket())
      h.view.bodies() shouldContainExactly listOf(QuickChatTexts.SELECTION_TOO_LARGE, QuickChatTexts.QUESTION_TOO_LONG)
      h.view.released.map { it.second } shouldContainExactly listOf(false, false)
      coVerify(exactly = 0) { h.service.ask(any()) }
    }

    it("an exception inside submit ends the send as Failed") {
      val h = harness()
      h.contextSource = { throw IllegalStateException("capture broke") }
      val t = ticket()
      h.session.submit(t)
      h.view.bodies() shouldContainExactly listOf(QuickChatTexts.INTERRUPTED)
      h.view.released shouldContainExactly listOf(t to false)
      h.conversation.inFlight.shouldBeNull()
    }
  }

  describe("the deadline (b)") {
    it("is fixed by submit's first statement, so a slow capture shortens the timer (A31)") {
      val h = harness()
      h.contextSource = {
        h.clock.advance(5.seconds)
        okContext(it)
      }
      h.session.submit(ticket())
      h.timer.entries.single().delayMillis shouldBe (QuickChatLimits.ANSWER_DEADLINE - 5.seconds).inWholeMilliseconds
      h.awaitRequest().deadlineNanos shouldBe 1_000 + QuickChatLimits.ANSWER_DEADLINE.inWholeNanoseconds
    }

    it("fires at once when the capture alone used up the deadline; the gate is closed first") {
      val h = harness()
      h.contextSource = {
        h.clock.advance(QuickChatLimits.ANSWER_DEADLINE)
        okContext(it)
      }
      val t = ticket()
      h.session.submit(t)
      h.view.bodies() shouldContainExactly listOf("What does this do?", QuickChatTexts.TIMED_OUT_BEFORE_SEND)
      h.view.released shouldContainExactly listOf(t to false)
      h.timer.entries shouldBe emptyList()
      // The job was cancelled at once; if it got as far as asking, its gate refuses to send.
      delay(50)
      h.requests.forEach { it.gate.tryBeginSend() shouldBe false }
    }

    it("before aiAction: nothing was sent and never will be") {
      val h = harness()
      h.session.submit(ticket())
      val request = h.awaitRequest()
      h.timer.fireAll()
      h.view.bodies().last() shouldBe QuickChatTexts.TIMED_OUT_BEFORE_SEND
      request.gate.state shouldBe SendGate.State.Closed
      request.gate.tryBeginSend() shouldBe false
    }

    it("while aiAction is in flight: MaybeSent") {
      val h = harness()
      h.session.submit(ticket())
      h.awaitRequest().gate.tryBeginSend() shouldBe true
      h.timer.fireAll()
      h.view.bodies().last() shouldBe QuickChatTexts.MAYBE_SENT
      h.conversation.binding.shouldBeNull()
    }

    it("after aiAction: timed out waiting, and the thread is kept for the next question") {
      val h = harness()
      h.session.submit(ticket())
      val gate = h.awaitRequest().gate
      gate.tryBeginSend() shouldBe true
      gate.markSent(update) shouldBe true
      h.timer.fireAll()
      h.view.bodies().last() shouldBe QuickChatTexts.TIMED_OUT_AFTER_SEND
      h.conversation.binding shouldBe update.toBinding()
    }
  }

  describe("finishOnce takes effect exactly once (A31)") {
    it("result then deadline: the late deadline is ignored") {
      val h = harness()
      val t = ticket()
      h.session.submit(t)
      h.answers.send(QuickChatOutcome.Answered("a", update))
      h.ui.awaitQueued(1) shouldBe true
      val watchdog = h.timer.entries.single()
      h.ui.drain()
      watchdog.cancelled shouldBe true
      watchdog.action() // a timer that fired anyway
      h.view.released shouldContainExactly listOf(t to true)
    }

    it("deadline then result: the late result is dropped and not stored (A24)") {
      val h = harness()
      val t = ticket()
      h.session.submit(t)
      h.awaitRequest()
      h.timer.fireAll()
      h.answers.send(QuickChatOutcome.Answered("late", update))
      h.ui.drain()
      h.view.released shouldContainExactly listOf(t to false)
      h.conversation.binding.shouldBeNull()
      h.view.bodies().last() shouldBe QuickChatTexts.TIMED_OUT_BEFORE_SEND
    }

    it("a result queued before the deadline fired is dropped when it runs after it") {
      val h = harness()
      val t = ticket()
      h.session.submit(t)
      h.answers.send(QuickChatOutcome.Answered("a", update))
      h.ui.awaitQueued(1) shouldBe true
      h.timer.fireAll()
      h.ui.drain()
      h.view.released.size shouldBe 1
      h.conversation.binding.shouldBeNull()
    }

    it("the scope cancelled while running: the completion hook ends the send as Interrupted (c)") {
      val h = harness()
      val t = ticket()
      h.session.submit(t)
      h.awaitRequest()
      h.runtime.close()
      h.ui.runNext() shouldBe true
      h.view.bodies().last() shouldBe QuickChatTexts.INTERRUPTED
      h.view.released shouldContainExactly listOf(t to false)
      h.timer.entries shouldBe emptyList()
    }

    it("the scope already cancelled before submit: Send still comes back") {
      val h = harness()
      h.runtime.close()
      val t = ticket()
      h.session.submit(t)
      h.ui.runNext() shouldBe true
      h.view.released shouldContainExactly listOf(t to false)
      h.view.bodies().last() shouldBe QuickChatTexts.INTERRUPTED
    }

    it("a failing render still releases, and a failing release still renders") {
      val h = harness()
      val t = ticket()
      h.session.submit(t)
      h.view.failRender = true
      h.answers.send(QuickChatOutcome.Answered("a", update))
      h.ui.runNext() shouldBe true
      h.view.released shouldContainExactly listOf(t to true)

      h.view.failRender = false
      h.view.failReleased = true
      val second = ticket("again")
      h.session.submit(second)
      val rendersBefore = h.view.rendered.size
      h.answers.send(QuickChatOutcome.EmptyAnswer(update))
      h.ui.runNext() shouldBe true
      h.view.released.last() shouldBe (second to false)
      h.view.rendered.size shouldBe rendersBefore + 1
      h.view.bodies().last() shouldBe QuickChatTexts.EMPTY_ANSWER
    }
  }

  describe("end (A10, A11, A24)") {
    it("closing while waiting cleans up without releasing; a late result does nothing") {
      val h = harness()
      h.session.submit(ticket())
      val request = h.awaitRequest()
      val watchdog = h.timer.entries.single()
      val rendersBefore = h.view.rendered.size
      h.session.end()
      watchdog.cancelled shouldBe true
      request.gate.state shouldBe SendGate.State.Closed
      h.conversation.inFlight.shouldBeNull()
      h.answers.send(QuickChatOutcome.Answered("late", update))
      h.ui.drain()
      h.view.released shouldBe emptyList()
      h.view.rendered.size shouldBe rendersBefore
      h.conversation.binding.shouldBeNull()
      h.session.end() // idempotent
    }

    it("end drops the binding") {
      val h = harness()
      h.conversation.binding = update.toBinding()
      h.session.end()
      h.conversation.binding.shouldBeNull()
    }

    it("reopening elsewhere: the old send's result never reaches the new popup") {
      val old = harness()
      old.session.submit(ticket("old"))
      old.awaitRequest()
      old.session.end()
      val fresh = harness(runtime = old.runtime)
      fresh.session.submit(ticket("new"))
      old.answers.send(QuickChatOutcome.Answered("old answer", update))
      old.ui.drain()
      fresh.ui.drain()
      fresh.view.bodies() shouldContainExactly listOf("new", QuickChatTexts.WAITING)
      fresh.view.released shouldBe emptyList()
      fresh.conversation.binding.shouldBeNull()
      fresh.session.end()
    }
  }

  describe("/clear and /reset (§9.4)") {
    it("/clear empties the pane, drops only the thread, releases as succeeded and sends M2") {
      val h = harness()
      val bound = update.toBinding()
      h.conversation.binding = bound
      h.conversation.add(QuickChatConversation.Entry.Question("q"))
      val t = ticket(" /CLEAR ")
      h.session.submit(t)
      h.view.bodies() shouldBe emptyList()
      h.view.released shouldContainExactly listOf(t to true)
      h.conversation.binding shouldBe bound.copy(threadId = null)
      coVerify(timeout = 5_000) { h.service.clear(bound, QuickChatCommand.Clear) }
      coVerify(exactly = 0) { h.service.ask(any()) }
    }

    it("/reset appends New chat; without a thread nothing is sent") {
      val h = harness()
      h.conversation.add(QuickChatConversation.Entry.Question("q"))
      h.session.submit(ticket("/reset"))
      h.view.bodies() shouldContainExactly listOf("q", QuickChatTexts.NEW_CHAT)
      delay(50)
      coVerify(exactly = 0) { h.service.clear(any(), any()) }
    }

    it("a stuck background clear does not hold Send, and is counted until it returns (A29)") {
      val h = harness()
      val stuck = StuckCall()
      cleanups.add(0) { stuck.release() }
      h.clearBlock = { stuck.block() }
      h.conversation.binding = update.toBinding()
      h.session.submit(ticket("/clear"))
      stuck.awaitEntered() shouldBe true
      QuickChatDetachedJobs.count shouldBe 1
      h.conversation.binding?.threadId.shouldBeNull()

      val next = ticket("next")
      h.session.submit(next)
      h.awaitRequest().binding?.threadId.shouldBeNull()
      h.answers.send(QuickChatOutcome.Answered("a", update))
      h.ui.runNext() shouldBe true
      h.view.released.last() shouldBe (next to true)

      stuck.release()
      awaitNoDetachedJobs() shouldBe true
    }
  }

  describe("the detached-job limit (A30)") {
    fun fillToLimit(): List<StuckCall> {
      val runtime = QuickChatRuntime().also { r -> cleanups += { r.close() } }
      return (1..QuickChatLimits.MAX_DETACHED).map {
        val stuck = StuckCall()
        cleanups.add(0) { stuck.release() }
        val job = runtime.scope.launch { runInterruptible { stuck.block() } }
        stuck.awaitEntered() shouldBe true
        job.cancel()
        QuickChatDetachedJobs.track(job)
        stuck
      }
    }

    it("at the limit a question is Busy and nothing is started; below it again, sending works") {
      val stuck = fillToLimit()
      val h = harness()
      val t = ticket()
      h.session.submit(t)
      h.view.bodies() shouldContainExactly listOf(QuickChatTexts.BUSY)
      h.view.released shouldContainExactly listOf(t to false)
      coVerify(exactly = 0) { h.service.ask(any()) }

      stuck.first().release()
      val until = System.currentTimeMillis() + 5_000
      while (QuickChatDetachedJobs.atLimit() && System.currentTimeMillis() < until) delay(5)
      h.session.submit(ticket("again"))
      h.awaitRequest()
      h.session.end()
    }

    it("at the limit /clear still takes effect locally but sends nothing") {
      fillToLimit()
      val h = harness()
      h.conversation.binding = update.toBinding()
      val t = ticket("/clear")
      h.session.submit(t)
      h.view.released shouldContainExactly listOf(t to true)
      h.conversation.binding?.threadId.shouldBeNull()
      delay(50)
      coVerify(exactly = 0) { h.service.clear(any(), any()) }
    }

    it("sends that are running normally are not counted: MAX_DETACHED + 1 conversations all send") {
      val runtime = QuickChatRuntime().also { r -> cleanups += { r.close() } }
      val sessions = (0..QuickChatLimits.MAX_DETACHED).map { harness(runtime = runtime) }
      sessions.forEach { it.session.submit(ticket()) }
      sessions.forEach { it.awaitRequest() }
      QuickChatDetachedJobs.count shouldBe 0
      sessions.forEach { it.view.bodies().last() shouldBe QuickChatTexts.WAITING }
      sessions.forEach { it.session.end() }
    }
  }
})
