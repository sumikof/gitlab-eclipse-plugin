package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.api.GraphQlException
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class QuickChatPollerTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val api = mockk<QuickChatApi>()
  val requestId = "req-1"
  val threadId = "thread-1"

  fun answer(content: String?, errors: List<String>? = emptyList(), id: String = requestId, role: String = "ASSISTANT") =
    AiMessageNode(id, role, content, errors, "2026-09-28T00:00:00Z")

  class Harness {
    val clock = FakeClock()
    val connections = FakeConnections()
    val sleeps = mutableListOf<kotlin.time.Duration>()
    fun poller(api: QuickChatApi) = QuickChatPoller(
      api,
      connections,
      clock,
      sleep = { d ->
        sleeps += d
        clock.advance(d)
      },
    )
    fun deadline(left: kotlin.time.Duration) = clock.nanoTime() + left.inWholeNanoseconds
  }

  beforeEach { clearMocks(api) }

  it("returns the first matching answer, waiting the first delay once") {
    val h = Harness()
    every { api.messages(any(), requestId, threadId, any()) } returns listOf(answer("Hello"))
    h.poller(api).await(INSTANCE, requestId, threadId, h.deadline(2.minutes)) shouldBe QuickChatPoller.Result.Answered("Hello")
    h.sleeps shouldBe listOf(1.seconds)
  }
  it("keeps polling at the interval until the answer appears") {
    val h = Harness()
    every { api.messages(any(), any(), any(), any()) } returnsMany listOf(emptyList(), emptyList(), listOf(answer("Hi")))
    h.poller(api).await(INSTANCE, requestId, threadId, h.deadline(2.minutes)) shouldBe QuickChatPoller.Result.Answered("Hi")
    h.sleeps shouldBe listOf(1.seconds, 3.seconds, 3.seconds)
  }
  it("ignores nodes of another requestId or role") {
    val h = Harness()
    every { api.messages(any(), any(), any(), any()) } returnsMany listOf(
      listOf(answer("x", id = "other"), answer("y", role = "USER")),
      listOf(answer("mine", role = "assistant")),
    )
    h.poller(api).await(INSTANCE, requestId, threadId, h.deadline(2.minutes)) shouldBe QuickChatPoller.Result.Answered("mine")
  }
  it("reports answer errors as Rejected even when content is present") {
    val h = Harness()
    every { api.messages(any(), any(), any(), any()) } returns listOf(answer("partial", listOf("e1", "e2")))
    h.poller(api).await(INSTANCE, requestId, threadId, h.deadline(2.minutes)) shouldBe
      QuickChatPoller.Result.Rejected(listOf("e1", "e2"))
  }
  it("reports a null, empty or blank answer as EmptyAnswer") {
    listOf(null, "", "  \n").forEach { content ->
      val h = Harness()
      every { api.messages(any(), any(), any(), any()) } returns listOf(answer(content, errors = null))
      h.poller(api).await(INSTANCE, requestId, threadId, h.deadline(2.minutes)) shouldBe QuickChatPoller.Result.EmptyAnswer
    }
  }
  it("stops at the deadline, never sleeping past it") {
    val h = Harness()
    every { api.messages(any(), any(), any(), any()) } returns emptyList()
    h.poller(api).await(INSTANCE, requestId, threadId, h.deadline(5.seconds)) shouldBe QuickChatPoller.Result.TimedOut
    h.sleeps shouldBe listOf(1.seconds, 3.seconds, 1.seconds)
    verify(exactly = 2) { api.messages(any(), any(), any(), any()) }
  }
  it("returns TimedOut without polling when the deadline has already passed") {
    val h = Harness()
    h.poller(api).await(INSTANCE, requestId, threadId, h.deadline(0.seconds)) shouldBe QuickChatPoller.Result.TimedOut
    h.sleeps shouldBe emptyList()
    verify(exactly = 0) { api.messages(any(), any(), any(), any()) }
  }
  it("bounds each poll by min(request timeout, time left)") {
    val h = Harness()
    every { api.messages(any(), any(), any(), any()) } returnsMany listOf(emptyList(), listOf(answer("a")))
    h.poller(api).await(INSTANCE, requestId, threadId, h.deadline(30.seconds))
    // 1 s in: 29 s left → capped at 25 s. 4 s in: 26 s left → still 25 s.
    verify(exactly = 2) { api.messages(any(), any(), any(), Duration.ofSeconds(25)) }

    val tight = Harness()
    every { api.messages(any(), any(), any(), any()) } returns listOf(answer("a"))
    tight.poller(api).await(INSTANCE, requestId, threadId, tight.deadline(20.seconds))
    verify { api.messages(any(), any(), any(), Duration.ofSeconds(19)) }
  }
  it("re-captures the bound connection for every poll and ends with ConnectionChanged when it moved (A23)") {
    val h = Harness()
    every { api.messages(any(), any(), any(), any()) } answers {
      h.connections.current = snapshot("https://gitlab.example.com")
      emptyList()
    }
    h.poller(api).await(INSTANCE, requestId, threadId, h.deadline(2.minutes)) shouldBe QuickChatPoller.Result.ConnectionChanged
    h.connections.captureIfCalls shouldBe listOf(INSTANCE, INSTANCE)
    verify(exactly = 1) { api.messages(any(), any(), any(), any()) }
  }
  it("lets a failed poll propagate, without retrying") {
    val h = Harness()
    every { api.messages(any(), any(), any(), any()) } throws GraphQlException(true, listOf("boom"))
    shouldThrow<GraphQlException> { h.poller(api).await(INSTANCE, requestId, threadId, h.deadline(2.minutes)) }
    verify(exactly = 1) { api.messages(any(), any(), any(), any()) }
  }
  it("stops promptly when cancelled during a blocked poll (interruptible)") {
    val entered = CountDownLatch(1)
    val never = CountDownLatch(1)
    every { api.messages(any(), any(), any(), any()) } answers {
      entered.countDown()
      never.await() // throws InterruptedException when runInterruptible interrupts it
      emptyList()
    }
    val poller = QuickChatPoller(api, FakeConnections(), sleep = {})
    val background = BackgroundScope()
    val job = background.scope.async {
      poller.await(INSTANCE, requestId, threadId, System.nanoTime() + 60.seconds.inWholeNanoseconds)
    }
    try {
      entered.await(5, TimeUnit.SECONDS) shouldBe true
      job.cancel()
      withTimeout(5.seconds) { job.join() }
      job.isCancelled shouldBe true
    } finally {
      never.countDown()
      background.close()
    }
  }
})
