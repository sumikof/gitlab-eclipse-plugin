package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.GraphQlException
import com.gitlab.eclipse.api.UnstableConnectionException
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.navigation.GitLabProjectInfo
import com.gitlab.eclipse.navigation.ProjectResolution
import com.google.gson.JsonSyntaxException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.Platform
import org.osgi.framework.Bundle
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException
import java.nio.channels.UnresolvedAddressException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val QUESTION = "What does this do? SECRET-QUESTION"
private const val THREAD = "gid://gitlab/Ai::Conversation::Thread/7"
private const val GID = "gid://gitlab/Project/1"

class QuickChatServiceTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val api = mockk<QuickChatApi>()
  val file = File("/work/proj/SecretFile.kt")
  val currentFile = CurrentFile("proj/SecretFile.kt", "val x = 1", "above", "below")
  val context = QuickChatContext(QUESTION, currentFile)

  fun project(namespaceWithPath: String = "group/proj") = GitLabProjectInfo(
    gitDir = File("/work/proj/.git"),
    workTree = File("/work/proj"),
    namespaceWithPath = namespaceWithPath,
    instanceUrl = INSTANCE,
    webUrl = "$INSTANCE/$namespaceWithPath",
    remoteName = "origin",
  )

  class Harness(api: QuickChatApi, clearDeadline: kotlin.time.Duration = 30.seconds) {
    val clock = FakeClock()
    val connections = FakeConnections()
    var resolution: ProjectResolution = ProjectResolution.NotInRepository
    val service = QuickChatService(
      api,
      connections,
      QuickChatPreflight(api) { resolution },
      QuickChatPoller(api, connections, clock, sleep = { clock.advance(it) }),
      clock,
      clearDeadline = clearDeadline,
      newSubscriptionId = { "sub-1" },
    )
  }

  fun Harness.request(
    binding: ConversationBinding? = null,
    left: kotlin.time.Duration = 120.seconds,
    gate: SendGate = SendGate(),
  ) = QuickChatRequest(context, file, binding, clock.nanoTime() + left.inWholeNanoseconds, gate)

  val firstUpdate = BindingUpdate(INSTANCE, PROJECT_PREFLIGHT, THREAD, projectChanged = false)
  val preflightOnly = BindingUpdate(INSTANCE, PROJECT_PREFLIGHT, null, projectChanged = false)

  beforeEach {
    clearMocks(api)
    every { api.version(any(), any()) } returns "17.10.0"
    every { api.project(any(), any(), any()) } returns ProjectInfo(GID, true)
    every { api.ask(any(), any(), any(), any(), any(), any(), any()) } returns AskResponse("req-1", emptyList(), THREAD)
    every { api.messages(any(), any(), any(), any()) } returns
      listOf(AiMessageNode("req-1", "ASSISTANT", "The answer", emptyList(), "t"))
  }

  describe("a send that completes") {
    it("answers and returns the binding update: instance, preflight and threadId") {
      val h = Harness(api).apply { resolution = ProjectResolution.Resolved(project()) }
      val gate = SendGate()
      val outcome = h.service.ask(h.request(gate = gate))
      outcome shouldBe QuickChatOutcome.Answered("The answer", firstUpdate)
      gate.state shouldBe SendGate.State.Sent(firstUpdate)
      h.connections.captureCalls shouldBe 1
      verify { api.ask(any(), QUESTION, currentFile, GID, null, "sub-1", any()) }
      verify { api.messages(any(), "req-1", THREAD, any()) }
    }
    it("continues a bound conversation: same instance only, bound threadId, preflight reused (A5)") {
      val h = Harness(api).apply { resolution = ProjectResolution.Resolved(project()) }
      val binding = ConversationBinding(INSTANCE, PROJECT_PREFLIGHT, THREAD)
      val outcome = h.service.ask(h.request(binding))
      outcome shouldBe QuickChatOutcome.Answered("The answer", firstUpdate)
      h.connections.captureCalls shouldBe 0
      h.connections.captureIfCalls.first() shouldBe INSTANCE
      verify(exactly = 0) { api.version(any(), any()) }
      verify { api.ask(any(), QUESTION, currentFile, GID, THREAD, any(), any()) }
    }
    it("normalizes the instance it binds to") {
      val h = Harness(api).apply { connections.current = snapshot("$INSTANCE/") }
      val outcome = h.service.ask(h.request()) as QuickChatOutcome.Answered
      outcome.update.instanceUrl shouldBe INSTANCE
    }
  }

  describe("connection changes (A23)") {
    it("sends nothing when the bound instance is no longer configured") {
      val h = Harness(api).apply { connections.current = snapshot("https://gitlab.example.com") }
      val gate = SendGate()
      h.service.ask(h.request(ConversationBinding(INSTANCE, PROJECT_PREFLIGHT, THREAD), gate = gate)) shouldBe
        QuickChatOutcome.ConnectionChanged
      verify(exactly = 0) { api.version(any(), any()) }
      verify(exactly = 0) { api.ask(any(), any(), any(), any(), any(), any(), any()) }
      gate.state shouldBe SendGate.State.Open
    }
    it("ends with ConnectionChanged when the instance changes while polling") {
      val h = Harness(api)
      every { api.ask(any(), any(), any(), any(), any(), any(), any()) } answers {
        h.connections.current = snapshot("https://gitlab.example.com")
        AskResponse("req-1", emptyList(), THREAD)
      }
      h.service.ask(h.request()) shouldBe QuickChatOutcome.ConnectionChanged
      verify(exactly = 0) { api.messages(any(), any(), any(), any()) }
    }
  }

  describe("project change (A26)") {
    it("redoes preflight, drops the old threadId and resourceId, and reports the change") {
      val h = Harness(api).apply { resolution = ProjectResolution.Resolved(project("group/other")) }
      every { api.project(any(), "group/other", any()) } returns ProjectInfo("gid://gitlab/Project/2", true)
      val binding = ConversationBinding(INSTANCE, PROJECT_PREFLIGHT, "old-thread")
      val outcome = h.service.ask(h.request(binding))
      verify { api.ask(any(), any(), any(), "gid://gitlab/Project/2", null, any(), any()) }
      val newPreflight = Preflight("gid://gitlab/Project/2", ProjectKey.resolved(INSTANCE, "group/other"))
      outcome shouldBe QuickChatOutcome.Answered("The answer", BindingUpdate(INSTANCE, newPreflight, THREAD, true))
    }
    it("keeps the project change in the update when the send then fails") {
      val h = Harness(api)
      every { api.ask(any(), any(), any(), any(), any(), any(), any()) } throws ConnectException()
      val binding = ConversationBinding(INSTANCE, PROJECT_PREFLIGHT, "old-thread")
      h.service.ask(h.request(binding)) shouldBe QuickChatOutcome.TransportFailed(
        TransportKind.CONNECT,
        null,
        null,
        BindingUpdate(INSTANCE, Preflight(null, ProjectKey.NOT_IN_REPOSITORY), null, true),
      )
    }
  }

  describe("preflight stops") {
    it("returns the preflight's outcome and sends nothing") {
      val h = Harness(api).apply { resolution = ProjectResolution.Failed }
      val gate = SendGate()
      h.service.ask(h.request(gate = gate)) shouldBe QuickChatOutcome.ProjectCheckFailed(ProjectCheckKind.RESOLUTION_FAILED)
      gate.state shouldBe SendGate.State.Open
      verify(exactly = 0) { api.ask(any(), any(), any(), any(), any(), any(), any()) }
    }
    it("returns Unsupported for an old instance") {
      every { api.version(any(), any()) } returns "17.9.3"
      Harness(api).let { it.service.ask(it.request()) } shouldBe QuickChatOutcome.Unsupported("17.9.3")
    }
  }

  describe("aiAction responses (w5)") {
    it("maps aiAction errors to ServerRejected, saving preflight and any threadId") {
      val h = Harness(api).apply { resolution = ProjectResolution.Resolved(project()) }
      every { api.ask(any(), any(), any(), any(), any(), any(), any()) } returns AskResponse(null, listOf("nope"), THREAD)
      val gate = SendGate()
      h.service.ask(h.request(gate = gate)) shouldBe QuickChatOutcome.ServerRejected(listOf("nope"), firstUpdate)
      gate.state shouldBe SendGate.State.Sending
    }
    it("maps a missing requestId to ServerRejected with no messages") {
      val h = Harness(api).apply { resolution = ProjectResolution.Resolved(project()) }
      every { api.ask(any(), any(), any(), any(), any(), any(), any()) } returns AskResponse(null, emptyList(), null)
      h.service.ask(h.request()) shouldBe QuickChatOutcome.ServerRejected(emptyList(), preflightOnly)
    }
    it("maps a missing threadId to Unsupported (K3), keeping the preflight") {
      val h = Harness(api).apply { resolution = ProjectResolution.Resolved(project()) }
      every { api.ask(any(), any(), any(), any(), any(), any(), any()) } returns AskResponse("req-1", emptyList(), null)
      h.service.ask(h.request()) shouldBe QuickChatOutcome.Unsupported(null, preflightOnly)
      verify(exactly = 0) { api.messages(any(), any(), any(), any()) }
    }
  }

  describe("answers (w6)") {
    it("maps answer errors to ServerRejected with the thread saved") {
      val h = Harness(api).apply { resolution = ProjectResolution.Resolved(project()) }
      every { api.messages(any(), any(), any(), any()) } returns
        listOf(AiMessageNode("req-1", "ASSISTANT", "c", listOf("bad"), "t"))
      h.service.ask(h.request()) shouldBe QuickChatOutcome.ServerRejected(listOf("bad"), firstUpdate)
    }
    it("maps an empty answer to EmptyAnswer") {
      val h = Harness(api).apply { resolution = ProjectResolution.Resolved(project()) }
      every { api.messages(any(), any(), any(), any()) } returns listOf(AiMessageNode("req-1", "ASSISTANT", "", null, "t"))
      h.service.ask(h.request()) shouldBe QuickChatOutcome.EmptyAnswer(firstUpdate)
    }
    it("times out after the send when no answer arrives by the deadline, keeping the thread") {
      val h = Harness(api).apply { resolution = ProjectResolution.Resolved(project()) }
      every { api.messages(any(), any(), any(), any()) } returns emptyList()
      h.service.ask(h.request()) shouldBe QuickChatOutcome.TimedOut(beforeSend = false, update = firstUpdate)
    }
  }

  describe("send gate (A28)") {
    it("returns no result and never sends when the UI closed the gate first") {
      val h = Harness(api)
      val gate = SendGate().apply { closeIfOpen() }
      h.service.ask(h.request(gate = gate)) shouldBe null
      verify(exactly = 0) { api.ask(any(), any(), any(), any(), any(), any(), any()) }
    }
    it("never sends when the UI closes the gate while the background is still in preflight") {
      val h = Harness(api)
      val gate = SendGate()
      val inPreflight = CountDownLatch(1)
      val release = CountDownLatch(1)
      every { api.version(any(), any()) } answers {
        inPreflight.countDown()
        release.await(5, TimeUnit.SECONDS)
        "17.10.0"
      }
      val background = BackgroundScope()
      try {
        val job = background.scope.async { h.service.ask(h.request(gate = gate)) }
        try {
          inPreflight.await(5, TimeUnit.SECONDS) shouldBe true
          gate.closeIfOpen() shouldBe SendGate.State.Open // the UI's deadline wins: "nothing was sent"
        } finally {
          release.countDown()
        }
        withTimeout(5.seconds) { job.await() } shouldBe null
      } finally {
        background.close()
      }
      verify(exactly = 0) { api.ask(any(), any(), any(), any(), any(), any(), any()) }
      gate.state shouldBe SendGate.State.Closed
    }
  }

  describe("deadline (w1)") {
    it("times out before sending, without capturing, when no time is left") {
      val h = Harness(api)
      h.service.ask(h.request(left = 0.seconds)) shouldBe QuickChatOutcome.TimedOut(beforeSend = true, update = null)
      h.connections.captureCalls shouldBe 0
    }
    it("times out before sending when preflight used up the time, keeping the preflight and the gate Open") {
      val h = Harness(api).apply { resolution = ProjectResolution.Resolved(project()) }
      every { api.project(any(), any(), any()) } answers {
        h.clock.advance(120.seconds)
        ProjectInfo(GID, true)
      }
      val gate = SendGate()
      h.service.ask(h.request(gate = gate)) shouldBe QuickChatOutcome.TimedOut(beforeSend = true, update = preflightOnly)
      gate.state shouldBe SendGate.State.Open
      verify(exactly = 0) { api.ask(any(), any(), any(), any(), any(), any(), any()) }
    }
    it("bounds aiAction by min(request timeout, time left)") {
      val h = Harness(api)
      h.service.ask(h.request(left = 10.seconds))
      verify { api.ask(any(), any(), any(), any(), any(), any(), Duration.ofSeconds(10)) }
      val roomy = Harness(api)
      roomy.service.ask(roomy.request(left = 100.seconds))
      verify { api.ask(any(), any(), any(), any(), any(), any(), Duration.ofSeconds(25)) }
    }
  }

  describe("exception classification (design §16)") {
    fun failed(kind: TransportKind, update: BindingUpdate?, status: Int? = null, cid: String? = null) =
      QuickChatOutcome.TransportFailed(kind, status, cid, update)

    it("maps connection capture failures to TransportFailed before anything is sent") {
      listOf(
        UnstableConnectionException() to failed(TransportKind.UNSTABLE_CONNECTION, null),
        IOException("x") to failed(TransportKind.IO, null),
        IllegalStateException("x") to failed(TransportKind.UNEXPECTED, null),
      ).forEach { (e, expected) ->
        val h = Harness(api).apply { connections.failWith = e }
        h.service.ask(h.request()) shouldBe expected
      }
    }
    it("maps preflight (Q1) failures to TransportFailed, or TimedOut(beforeSend) once the deadline passed") {
      val h = Harness(api)
      listOf(
        GitLabApiException(502, "body", "cid-1") to failed(TransportKind.HTTP, null, 502, "cid-1"),
        GraphQlException(true, listOf("m"), "cid-2") to failed(TransportKind.GRAPHQL, null, cid = "cid-2"),
        JsonSyntaxException("x") to failed(TransportKind.INVALID_RESPONSE, null),
        ConnectException() to failed(TransportKind.CONNECT, null),
        UnresolvedAddressException() to failed(TransportKind.CONNECT, null),
        HttpConnectTimeoutException("x") to failed(TransportKind.CONNECT, null),
        HttpTimeoutException("x") to failed(TransportKind.TIMEOUT, null),
        IOException("x") to failed(TransportKind.IO, null),
        IllegalStateException("x") to failed(TransportKind.UNEXPECTED, null),
      ).forEach { (e, expected) ->
        every { api.version(any(), any()) } throws e
        h.service.ask(h.request()) shouldBe expected
      }
      every { api.version(any(), any()) } answers {
        h.clock.advance(200.seconds)
        throw HttpTimeoutException("x")
      }
      h.service.ask(h.request()) shouldBe QuickChatOutcome.TimedOut(beforeSend = true, update = null)
    }
    it("maps aiAction failures: not connected → TransportFailed, sent-but-unknown → MaybeSent") {
      val h = Harness(api).apply { resolution = ProjectResolution.Resolved(project()) }
      listOf(
        ConnectException() to failed(TransportKind.CONNECT, preflightOnly),
        UnresolvedAddressException() to failed(TransportKind.CONNECT, preflightOnly),
        HttpConnectTimeoutException("x") to failed(TransportKind.CONNECT, preflightOnly),
        GitLabApiException(500, "body", "cid-3") to failed(TransportKind.HTTP, preflightOnly, 500, "cid-3"),
        GraphQlException(true, listOf("m"), "cid-4") to failed(TransportKind.GRAPHQL, preflightOnly, cid = "cid-4"),
        UnstableConnectionException() to failed(TransportKind.UNSTABLE_CONNECTION, preflightOnly),
        JsonSyntaxException("result unknown") to QuickChatOutcome.MaybeSent(preflightOnly),
        HttpTimeoutException("x") to QuickChatOutcome.MaybeSent(preflightOnly),
        IOException("reset") to QuickChatOutcome.MaybeSent(preflightOnly),
        IllegalStateException("x") to QuickChatOutcome.MaybeSent(preflightOnly),
      ).forEach { (e, expected) ->
        every { api.ask(any(), any(), any(), any(), any(), any(), any()) } throws e
        h.service.ask(h.request()) shouldBe expected
      }
    }
    it("maps polling failures to TransportFailed with the thread saved, or TimedOut after the deadline") {
      val h = Harness(api).apply { resolution = ProjectResolution.Resolved(project()) }
      listOf(
        GitLabApiException(503, "body", "cid-5") to failed(TransportKind.HTTP, firstUpdate, 503, "cid-5"),
        JsonSyntaxException("x") to failed(TransportKind.INVALID_RESPONSE, firstUpdate),
        HttpTimeoutException("x") to failed(TransportKind.TIMEOUT, firstUpdate),
        IOException("x") to failed(TransportKind.IO, firstUpdate),
      ).forEach { (e, expected) ->
        every { api.messages(any(), any(), any(), any()) } throws e
        h.service.ask(h.request()) shouldBe expected
      }
      every { api.messages(any(), any(), any(), any()) } answers {
        h.clock.advance(200.seconds)
        throw HttpTimeoutException("x")
      }
      h.service.ask(h.request()) shouldBe QuickChatOutcome.TimedOut(beforeSend = false, update = firstUpdate)
    }
  }

  describe("cancellation") {
    it("rethrows CancellationException instead of turning it into an outcome") {
      every { api.ask(any(), any(), any(), any(), any(), any(), any()) } throws CancellationException("stop")
      val h = Harness(api)
      shouldThrow<CancellationException> { h.service.ask(h.request()) }
    }
    it("interrupts a blocked aiAction when the coroutine is cancelled") {
      val entered = CountDownLatch(1)
      val never = CountDownLatch(1)
      every { api.ask(any(), any(), any(), any(), any(), any(), any()) } answers {
        entered.countDown()
        never.await()
        AskResponse("req-1", emptyList(), THREAD)
      }
      val h = Harness(api)
      val background = BackgroundScope()
      val job = background.scope.async { h.service.ask(h.request()) }
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
  }

  describe("logging (NFR-4, A17)") {
    it("logs kinds and class names only — never the question, file, or server text") {
      val log = mockk<ILog>(relaxUnitFun = true)
      val messages = mutableListOf<String>()
      every { log.info(capture(messages)) } returns Unit
      every { log.warn(capture(messages)) } returns Unit
      every { Platform.getLog(any<Bundle>()) } returns log
      val h = Harness(api)
      every { api.ask(any(), any(), any(), any(), any(), any(), any()) } throws
        GraphQlException(true, listOf("echo: $QUESTION"), "cid-9")
      h.service.ask(h.request())
      every { api.ask(any(), any(), any(), any(), any(), any(), any()) } returns
        AskResponse("req-1", listOf("echo: $QUESTION"), THREAD)
      h.service.ask(h.request())
      messages.isNotEmpty() shouldBe true
      messages.none { "SECRET" in it || "SecretFile" in it || "echo" in it } shouldBe true
      verify(exactly = 0) { log.warn(any(), any()) }
      verify(exactly = 0) { log.error(any(), any()) }
    }
  }

  describe("clear (design §9.4, §15.4)") {
    val bound = ConversationBinding(INSTANCE, PROJECT_PREFLIGHT, THREAD)

    it("sends nothing without a thread") {
      val h = Harness(api)
      h.service.clear(bound.copy(threadId = null), QuickChatCommand.Clear) shouldBe ClearResult.NO_THREAD
      h.connections.captureIfCalls shouldBe emptyList()
    }
    it("sends /clear or /reset for the bound thread on the bound instance only") {
      every { api.clear(any(), any(), any(), any()) } returns ClearResponse("r", emptyList())
      val h = Harness(api)
      h.service.clear(bound, QuickChatCommand.Clear) shouldBe ClearResult.SENT
      h.service.clear(bound, QuickChatCommand.Reset) shouldBe ClearResult.SENT
      verify { api.clear(any(), "/clear", THREAD, Duration.ofSeconds(25)) }
      verify { api.clear(any(), "/reset", THREAD, Duration.ofSeconds(25)) }
      h.connections.captureIfCalls shouldBe listOf(INSTANCE, INSTANCE)
      h.connections.captureCalls shouldBe 0
    }
    it("does not send when the instance changed") {
      val h = Harness(api).apply { connections.current = snapshot("https://gitlab.example.com") }
      h.service.clear(bound, QuickChatCommand.Clear) shouldBe ClearResult.CONNECTION_CHANGED
      verify(exactly = 0) { api.clear(any(), any(), any(), any()) }
    }
    it("reports rejection and failures without throwing") {
      val h = Harness(api)
      every { api.clear(any(), any(), any(), any()) } returns ClearResponse("r", listOf("secret text"))
      h.service.clear(bound, QuickChatCommand.Clear) shouldBe ClearResult.REJECTED
      every { api.clear(any(), any(), any(), any()) } throws GraphQlException(true, listOf("x"))
      h.service.clear(bound, QuickChatCommand.Clear) shouldBe ClearResult.FAILED
    }
    it("gives up at CLEAR_DEADLINE by interrupting a blocked request") {
      val never = CountDownLatch(1)
      every { api.clear(any(), any(), any(), any()) } answers {
        never.await()
        ClearResponse("r", emptyList())
      }
      try {
        val service = Harness(api, clearDeadline = 200.milliseconds).service
        withTimeout(5.seconds) { service.clear(bound, QuickChatCommand.Clear) } shouldBe ClearResult.TIMED_OUT
      } finally {
        never.countDown()
      }
    }
    it("rethrows an outer cancellation") {
      every { api.clear(any(), any(), any(), any()) } throws CancellationException("stop")
      shouldThrow<CancellationException> { Harness(api).service.clear(bound, QuickChatCommand.Clear) }
    }
  }
})
