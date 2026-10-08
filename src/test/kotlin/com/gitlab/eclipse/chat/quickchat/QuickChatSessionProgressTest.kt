package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.views.inlinethread.InlineThreadModel
import com.gitlab.eclipse.views.inlinethread.SubmitTicket
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

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

    /** How long each render takes: the fake clock advances by it inside `render` (a slow SWT rebuild). */
    var renderCost: Duration = Duration.ZERO
    private val timedView = object : QuickChatView {
      override fun render(model: InlineThreadModel) {
        clock.advance(renderCost)
        view.render(model)
      }

      override fun released(ticket: SubmitTicket, succeeded: Boolean) = view.released(ticket, succeeded)
    }
    val conversation = QuickChatConversation()
    val runtime = QuickChatRuntime(clock)
    val service = mockk<QuickChatService>()
    val session = QuickChatSession(
      conversation,
      runtime,
      service,
      { okContext(it) },
      { null },
      timedView,
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

    /** The one scheduled partial render, whatever its delay: every timer entry but the deadline watchdog. */
    fun renderEntry(watchdog: List<FakeTimer.Entry>) = timer.entries.filterNot { it in watchdog }.single()

    /** Fires the scheduled partial render, taking [cost], and returns the delay it was scheduled with. */
    fun fireRender(watchdog: List<FakeTimer.Entry>, cost: Duration): Long {
      val entry = renderEntry(watchdog)
      timer.entries.remove(entry)
      renderCost = cost
      entry.action()
      renderCost = Duration.ZERO
      return entry.delayMillis
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

  describe("adaptive render interval (§9.4, Codex PR #105 P1)") {
    val factor = QuickChatStreamLimits.RENDER_BACKOFF_FACTOR

    it("a slow render widens the next interval to factor × its duration; a fast one restores the minimum") {
      factor shouldBe 3
      val h = harness()
      val t = ticket("q")
      h.session.submit(t)
      val gen = h.conversation.inFlight!!.generation
      val watchdog = h.timer.entries.toList()

      h.session.onProgress(t, gen) { "a" }
      h.fireRender(watchdog, 200.milliseconds) shouldBe renderMillis
      h.session.onProgress(t, gen) { "ab" }
      h.renderEntry(watchdog).delayMillis shouldBe 600
      h.fireRender(watchdog, 10.milliseconds) shouldBe 600
      h.session.onProgress(t, gen) { "abc" }
      h.renderEntry(watchdog).delayMillis shouldBe renderMillis
      h.view.bodies().last() shouldBe "ab\n\n${QuickChatTexts.ANSWER_IN_PROGRESS}"
    }

    it("a render far below the minimum keeps the minimum interval") {
      val h = harness()
      val t = ticket("q")
      h.session.submit(t)
      val gen = h.conversation.inFlight!!.generation
      val watchdog = h.timer.entries.toList()
      h.session.onProgress(t, gen) { "a" }
      h.fireRender(watchdog, 1.milliseconds)
      h.session.onProgress(t, gen) { "ab" }
      h.renderEntry(watchdog).delayMillis shouldBe renderMillis
    }

    it("a failing render is measured too") {
      val h = harness()
      val t = ticket("q")
      h.session.submit(t)
      val gen = h.conversation.inFlight!!.generation
      val watchdog = h.timer.entries.toList()
      h.view.failRender = true
      h.session.onProgress(t, gen) { "a" }
      h.fireRender(watchdog, 200.milliseconds)
      h.view.failRender = false
      h.session.onProgress(t, gen) { "ab" }
      h.renderEntry(watchdog).delayMillis shouldBe 600
    }

    it("a huge render duration saturates instead of overflowing") {
      val h = harness()
      val t = ticket("q")
      h.session.submit(t)
      val gen = h.conversation.inFlight!!.generation
      val watchdog = h.timer.entries.toList()
      h.session.onProgress(t, gen) { "a" }
      h.fireRender(watchdog, (Long.MAX_VALUE / 2).nanoseconds)
      h.session.onProgress(t, gen) { "ab" }
      val delay = h.renderEntry(watchdog).delayMillis
      (delay > Long.MAX_VALUE / 1_000_000 / 2) shouldBe true
    }

    it("renders occupy at most 1 / (1 + factor) of the UI thread, cycle after cycle") {
      val h = harness()
      val t = ticket("q")
      h.session.submit(t)
      val gen = h.conversation.inFlight!!.generation
      val watchdog = h.timer.entries.toList()
      h.session.onProgress(t, gen) { "a" }
      h.fireRender(watchdog, 300.milliseconds)
      repeat(5) { i ->
        h.session.onProgress(t, gen) { "a$i" }
        val delay = h.fireRender(watchdog, 300.milliseconds)
        (delay >= 900) shouldBe true
        // The share: render / (gap + render) ≤ 1 / (1 + factor).
        (300.0 / (delay + 300) <= 1.0 / (1 + factor)) shouldBe true
      }
    }
  }
})
