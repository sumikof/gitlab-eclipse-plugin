package com.gitlab.eclipse.chat.quickchat

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SendGateTest : DescribeSpec({
  val update = BindingUpdate(INSTANCE, PROJECT_PREFLIGHT, "gid://gitlab/Ai::Conversation::Thread/1", false)

  it("starts Open") {
    SendGate().state shouldBe SendGate.State.Open
  }
  it("moves Open → Sending → Sent(update) on the background path") {
    val gate = SendGate()
    gate.tryBeginSend() shouldBe true
    gate.state shouldBe SendGate.State.Sending
    gate.markSent(update) shouldBe true
    gate.state shouldBe SendGate.State.Sent(update)
  }
  it("closeIfOpen closes an Open gate, returns Open, and then refuses every send") {
    val gate = SendGate()
    gate.closeIfOpen() shouldBe SendGate.State.Open
    gate.state shouldBe SendGate.State.Closed
    gate.tryBeginSend() shouldBe false
    gate.markSent(update) shouldBe false
    gate.state shouldBe SendGate.State.Closed
  }
  it("closeIfOpen leaves Sending and Sent alone and reports them") {
    val sending = SendGate().apply { tryBeginSend() }
    sending.closeIfOpen() shouldBe SendGate.State.Sending
    sending.state shouldBe SendGate.State.Sending

    val sent = SendGate().apply {
      tryBeginSend()
      markSent(update)
    }
    sent.closeIfOpen() shouldBe SendGate.State.Sent(update)
    sent.state shouldBe SendGate.State.Sent(update)
  }
  it("closeIfOpen on a Closed gate reports Closed") {
    val gate = SendGate().apply { closeIfOpen() }
    gate.closeIfOpen() shouldBe SendGate.State.Closed
  }
  it("tryBeginSend succeeds only once") {
    val gate = SendGate()
    gate.tryBeginSend() shouldBe true
    gate.tryBeginSend() shouldBe false
  }
  it("markSent without tryBeginSend does nothing") {
    val gate = SendGate()
    gate.markSent(update) shouldBe false
    gate.state shouldBe SendGate.State.Open
  }
  it("lets exactly one side win a simultaneous tryBeginSend / closeIfOpen (A28)") {
    val pool = Executors.newFixedThreadPool(2)
    try {
      repeat(500) {
        val gate = SendGate()
        val barrier = CyclicBarrier(2)
        val send = pool.submit<Boolean> {
          barrier.await(5, TimeUnit.SECONDS)
          gate.tryBeginSend()
        }
        val close = pool.submit<SendGate.State> {
          barrier.await(5, TimeUnit.SECONDS)
          gate.closeIfOpen()
        }
        val sent = send.get(5, TimeUnit.SECONDS)
        val before = close.get(5, TimeUnit.SECONDS)
        if (sent) {
          before shouldBe SendGate.State.Sending
          gate.state shouldBe SendGate.State.Sending
        } else {
          before shouldBe SendGate.State.Open
          gate.state shouldBe SendGate.State.Closed
        }
      }
    } finally {
      pool.shutdownNow()
    }
  }
})
