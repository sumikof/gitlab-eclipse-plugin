package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CountDownLatch

class ResultSinkTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val outcome = QuickChatOutcome.Busy

  it("deliver posts a finish that re-reads the session on the UI thread") {
    val ui = FakeUi()
    val session = mockk<QuickChatSession>(relaxed = true)
    val t = ticket()
    val sink = ResultSink(session, t, 3, ui.runOnUi)
    sink.deliver(outcome)
    verify(exactly = 0) { session.finishOnce(any(), any(), any()) }
    ui.drain()
    verify(exactly = 1) { session.finishOnce(t, 3, outcome) }
  }

  it("completed posts Interrupted only when nothing was delivered") {
    val ui = FakeUi()
    val session = mockk<QuickChatSession>(relaxed = true)
    val t = ticket()
    val quiet = ResultSink(session, t, 0, ui.runOnUi)
    quiet.completed()
    ui.drain()
    verify(exactly = 1) { session.finishOnce(t, 0, QuickChatOutcome.Interrupted) }

    val delivered = ResultSink(session, t, 1, ui.runOnUi)
    delivered.deliver(outcome)
    delivered.completed()
    ui.queue.size shouldBe 1
    ui.drain()
    verify(exactly = 0) { session.finishOnce(t, 1, QuickChatOutcome.Interrupted) }
  }

  it("an action posted before detach does nothing when it runs after it (A33 race)") {
    val ui = FakeUi()
    val session = mockk<QuickChatSession>(relaxed = true)
    val sink = ResultSink(session, ticket(), 0, ui.runOnUi)
    sink.deliver(outcome)
    sink.completed()
    sink.detach()
    ui.drain()
    verify(exactly = 0) { session.finishOnce(any(), any(), any()) }
  }

  it("a failing runOnUi (display gone) is swallowed") {
    val session = mockk<QuickChatSession>(relaxed = true)
    val sink = ResultSink(session, ticket(), 0) { throw IllegalStateException("disposed") }
    sink.deliver(outcome)
    sink.completed()
    verify(exactly = 0) { session.finishOnce(any(), any(), any()) }
  }

  it("progress posts at most one UI action until it runs, and the session gets the latest source") {
    val ui = FakeUi()
    val session = mockk<QuickChatSession>(relaxed = true)
    val t = ticket()
    val sink = ResultSink(session, t, 2, ui.runOnUi)
    val first = { "a" }
    val second = { "ab" }
    sink.progress(first)
    sink.progress(second)
    ui.queue.size shouldBe 1
    ui.drain()
    verify(exactly = 1) { session.onProgress(t, 2, second) }
    verify(exactly = 0) { session.onProgress(t, 2, first) }
    sink.progress(first)
    ui.queue.size shouldBe 1
  }

  it("progress after detach posts nothing, and a queued one does nothing") {
    val ui = FakeUi()
    val session = mockk<QuickChatSession>(relaxed = true)
    val sink = ResultSink(session, ticket(), 0, ui.runOnUi)
    sink.progress { "a" }
    sink.detach()
    sink.progress { "b" }
    ui.queue.size shouldBe 1
    ui.drain()
    verify(exactly = 0) { session.onProgress(any(), any(), any()) }
  }

  it("concurrent progress from two threads never queues more than one action") {
    val ui = FakeUi()
    val session = mockk<QuickChatSession>(relaxed = true)
    val sink = ResultSink(session, ticket(), 0, ui.runOnUi)
    val start = CountDownLatch(1)
    val threads = List(2) { n ->
      Thread {
        start.await()
        repeat(10_000) { i -> sink.progress { "$n-$i" } }
      }
    }
    threads.forEach(Thread::start)
    start.countDown()
    threads.forEach { it.join(10_000) }
    threads.none { it.isAlive } shouldBe true
    ui.queue.size shouldBe 1
  }

  it("a failing runOnUi for progress is swallowed and a later progress tries again") {
    var fail = true
    val ui = FakeUi()
    val session = mockk<QuickChatSession>(relaxed = true)
    val runOnUi: (() -> Unit) -> Unit = { action ->
      check(!fail) { "disposed" }
      ui.runOnUi(action)
    }
    val sink = ResultSink(session, ticket(), 0, runOnUi)
    sink.progress { "a" }
    ui.queue.size shouldBe 0
    fail = false
    sink.progress { "b" }
    ui.queue.size shouldBe 1
  }

  it("a progress arriving while the posted action runs is posted again, not lost") {
    val ui = FakeUi()
    val session = mockk<QuickChatSession>(relaxed = true)
    val t = ticket()
    val sink = ResultSink(session, t, 0, ui.runOnUi)
    val late = { "abc" }
    every { session.onProgress(t, 0, any()) } answers { sink.progress(late) } andThen Unit
    sink.progress { "a" }
    ui.runNext() shouldBe true
    ui.queue.size shouldBe 1
    ui.drain()
    verify(exactly = 1) { session.onProgress(t, 0, late) }
  }

  it("a progress suppressed while a failing post was in flight is posted once more (Codex PR #105 P2)") {
    val ui = FakeUi()
    val session = mockk<QuickChatSession>(relaxed = true)
    val t = ticket()
    lateinit var sink: ResultSink
    val second = { "ab" }
    var calls = 0
    val runOnUi: (() -> Unit) -> Unit = { action ->
      calls++
      if (calls == 1) {
        // The concurrent callback: it sees a post in flight and leaves it to this one.
        sink.progress(second)
        error("disposed")
      }
      ui.runOnUi(action)
    }
    sink = ResultSink(session, t, 0, runOnUi)
    sink.progress { "a" }
    calls shouldBe 2
    ui.queue.size shouldBe 1
    ui.drain()
    verify(exactly = 1) { session.onProgress(t, 0, second) }
  }

  it("a runOnUi that always fails is retried at most once per progress, never in a loop") {
    val session = mockk<QuickChatSession>(relaxed = true)
    lateinit var sink: ResultSink
    var calls = 0
    sink = ResultSink(session, ticket(), 0) {
      calls++
      sink.progress { "newer $calls" }
      error("disposed")
    }
    sink.progress { "a" }
    calls shouldBe 2
    sink.progress { "b" }
    calls shouldBe 4
  }
})
