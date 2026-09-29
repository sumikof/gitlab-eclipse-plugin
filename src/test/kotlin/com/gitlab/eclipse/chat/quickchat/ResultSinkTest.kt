package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify

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
})
