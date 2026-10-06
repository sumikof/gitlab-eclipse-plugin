package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation

/** Streamed partial answers in the popup (streaming design §9.4, §9.5, §17). */
class QuickChatSessionProgressTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val update = BindingUpdate(INSTANCE, PROJECT_PREFLIGHT, null, projectChanged = false)
  val renderMillis = QuickChatStreamLimits.RENDER_INTERVAL.inWholeMilliseconds
  val cleanups = mutableListOf<() -> Unit>()

  class Harness {
    val clock = FakeClock(1_000)
    val ui = FakeUi()
    val timer = FakeTimer()
    val view = RecordingView()
    val conversation = QuickChatConversation()
    val runtime = QuickChatRuntime(clock)
    val service = mockk<QuickChatService>()
    val session = QuickChatSession(
      conversation,
      runtime,
      service,
      { okContext(it) },
      { null },
      view,
      ui.runOnUi,
      timer.schedule,
      clock,
    )

    init {
      // The send stays in flight until the session cancels it.
      coEvery { service.ask(any()) } coAnswers { awaitCancellation() }
    }

    /** The scheduled partial renders (the deadline watchdog is the other timer entry). */
    fun renders(delayMillis: Long) = timer.entries.filter { it.delayMillis == delayMillis }

    fun fireRenders(delayMillis: Long) = renders(delayMillis).forEach {
      timer.entries.remove(it)
      it.action()
    }
  }

  fun harness() = Harness().also { h ->
    cleanups += {
      h.session.end()
      h.runtime.close()
    }
  }

  afterEach {
    cleanups.forEach { it() }
    cleanups.clear()
    awaitNoDetachedJobs() shouldBe true
  }

  it("a partial is rendered once per interval, with the latest text read only at render time") {
    val h = harness()
    val t = ticket("q")
    h.session.submit(t)
    val gen = h.conversation.inFlight!!.generation
    h.view.rendered.size shouldBe 1
    var reads = 0
    h.session.onProgress(t, gen) {
      reads++
      "He"
    }
    h.session.onProgress(t, gen) {
      reads++
      "Hello"
    }
    h.renders(renderMillis).size shouldBe 1
    renderMillis shouldBe 150
    reads shouldBe 0
    h.view.rendered.size shouldBe 1

    h.fireRenders(renderMillis)
    reads shouldBe 1
    h.view.rendered.size shouldBe 2
    h.view.bodies().last() shouldBe "Hello\n\n${QuickChatTexts.ANSWER_IN_PROGRESS}"

    h.session.onProgress(t, gen) { "Hello world" }
    h.renders(renderMillis).size shouldBe 1
    h.fireRenders(renderMillis)
    h.view.bodies().last() shouldBe "Hello world\n\n${QuickChatTexts.ANSWER_IN_PROGRESS}"
  }

  it("progress for a send that is no longer current is dropped") {
    val h = harness()
    val t = ticket("q")
    h.session.submit(t)
    val gen = h.conversation.inFlight!!.generation
    h.session.onProgress(ticket("q"), gen) { "other ticket" }
    h.session.onProgress(t, gen + 1) { "later generation" }
    h.renders(renderMillis) shouldBe emptyList()
    h.view.rendered.size shouldBe 1
  }

  it("finishOnce cancels the scheduled render and the result is what stays shown") {
    val h = harness()
    val t = ticket("q")
    h.session.submit(t)
    val gen = h.conversation.inFlight!!.generation
    h.session.onProgress(t, gen) { "partial" }
    val scheduled = h.renders(renderMillis).single()

    h.session.finishOnce(t, gen, QuickChatOutcome.Answered("full", update))
    scheduled.cancelled shouldBe true
    h.timer.entries shouldBe emptyList()
    h.view.bodies().last() shouldBe "full"
    val renders = h.view.rendered.size

    // Should the cancelled timer still fire (it raced the cancel), it renders nothing.
    scheduled.action()
    h.view.rendered.size shouldBe renders
    h.view.bodies().last() shouldBe "full"
  }

  it("progress delivered after the result (late stream callback) never overwrites it") {
    val h = harness()
    val t = ticket("q")
    h.session.submit(t)
    val gen = h.conversation.inFlight!!.generation
    h.session.finishOnce(t, gen, QuickChatOutcome.Answered("full", update))
    val renders = h.view.rendered.size

    h.session.onProgress(t, gen) { "late" }
    h.timer.entries shouldBe emptyList()
    h.view.rendered.size shouldBe renders
    h.view.bodies() shouldContainExactly listOf("q", "full")
  }

  it("end() (popup closed) cancels the scheduled render") {
    val h = harness()
    val t = ticket("q")
    h.session.submit(t)
    val gen = h.conversation.inFlight!!.generation
    h.session.onProgress(t, gen) { "partial" }
    val scheduled = h.renders(renderMillis).single()
    val renders = h.view.rendered.size

    h.session.end()
    scheduled.cancelled shouldBe true
    h.timer.entries shouldBe emptyList()
    scheduled.action()
    h.view.rendered.size shouldBe renders
  }

  it("a replacing submit cancels the old send's scheduled render") {
    val h = harness()
    val t = ticket("one")
    h.session.submit(t)
    val gen = h.conversation.inFlight!!.generation
    h.session.onProgress(t, gen) { "partial" }
    val scheduled = h.renders(renderMillis).single()

    h.session.submit(ticket("two"))
    scheduled.cancelled shouldBe true
    h.renders(renderMillis) shouldBe emptyList()
  }

  it("a failing source is contained and the send goes on") {
    val h = harness()
    val t = ticket("q")
    h.session.submit(t)
    val gen = h.conversation.inFlight!!.generation
    h.session.onProgress(t, gen) { error("boom") }
    h.fireRenders(renderMillis)
    h.view.bodies() shouldContainExactly listOf("q", QuickChatTexts.WAITING)

    h.session.finishOnce(t, gen, QuickChatOutcome.Answered("full", update))
    h.view.bodies() shouldContainExactly listOf("q", "full")
    h.view.released shouldContainExactly listOf(t to true)
  }

  it("the wired send passes progress through the sink to the session") {
    val h = harness()
    coEvery { h.service.ask(any()) } coAnswers {
      firstArg<QuickChatRequest>().onProgress { "chunk" }
      awaitCancellation()
    }
    h.session.submit(ticket("q"))
    h.ui.runNext() shouldBe true
    h.renders(renderMillis).size shouldBe 1
    h.fireRenders(renderMillis)
    h.view.bodies().last() shouldBe "chunk\n\n${QuickChatTexts.ANSWER_IN_PROGRESS}"
  }
})
