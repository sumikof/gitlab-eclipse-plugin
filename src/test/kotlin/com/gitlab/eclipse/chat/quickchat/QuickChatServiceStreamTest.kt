package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.navigation.GitLabProjectInfo
import com.gitlab.eclipse.navigation.ProjectResolution
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.Platform
import org.osgi.framework.Bundle
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val QUESTION = "What does this do? SECRET-QUESTION"
private const val THREAD = "gid://gitlab/Ai::Conversation::Thread/7"
private const val GID = "gid://gitlab/Project/1"
private const val REQUEST_ID = "req-1"

/** A [QuickChatStreams] that opens a real [AiCompletionStream] without a socket, or fails. */
private class FakeStreams(
  private val result: suspend (onProgress: () -> Unit) -> StreamOpenResult,
) : QuickChatStreams {
  var calls = 0
  var lastSubscriptionId: String? = null
  var lastWait: Duration? = null
  var lastOnProgress: (() -> Unit)? = null
  var opened: AiCompletionStream? = null

  override suspend fun open(
    connection: ConnectionSnapshot,
    clientSubscriptionId: String,
    waitLimit: Duration,
    onProgress: () -> Unit,
  ): StreamOpenResult {
    calls++
    lastSubscriptionId = clientSubscriptionId
    lastWait = waitLimit
    lastOnProgress = onProgress
    return result(onProgress).also { (it as? StreamOpenResult.Opened)?.let { o -> opened = o.stream } }
  }

  companion object {
    fun opened() = FakeStreams { StreamOpenResult.Opened(AiCompletionStream("id", it)) }

    fun failed(reason: StreamOpenFailure) = FakeStreams { StreamOpenResult.Failed(reason) }
  }
}

/** One `GraphqlChannel` message carrying [response] as `aiCompletionResponse`. */
private fun channelMessage(response: String): JsonObject =
  JsonParser.parseString("""{"result":{"data":{"aiCompletionResponse":$response}},"more":true}""").asJsonObject

private fun chunk(id: Int, content: String, requestId: String = REQUEST_ID): JsonObject =
  channelMessage("""{"requestId":"$requestId","role":"ASSISTANT","content":"$content","chunkId":$id}""")

class QuickChatServiceStreamTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val api = mockk<QuickChatApi>()
  val file = File("/work/proj/SecretFile.kt")
  val currentFile = CurrentFile("proj/SecretFile.kt", "val x = 1", "above", "below")
  val context = QuickChatContext(QUESTION, currentFile)

  val project = GitLabProjectInfo(
    gitDir = File("/work/proj/.git"),
    workTree = File("/work/proj"),
    namespaceWithPath = "group/proj",
    instanceUrl = INSTANCE,
    webUrl = "$INSTANCE/group/proj",
    remoteName = "origin",
  )

  class Harness(api: QuickChatApi, streams: QuickChatStreams?, project: GitLabProjectInfo) {
    val clock = FakeClock()
    val connections = FakeConnections()
    val service = QuickChatService(
      api,
      connections,
      QuickChatPreflight(api) { ProjectResolution.Resolved(project) },
      QuickChatPoller(api, connections, clock, sleep = { clock.advance(it) }),
      clock,
      newSubscriptionId = { "sub-1" },
      streams = streams,
    )
  }

  fun harness(streams: QuickChatStreams?) = Harness(api, streams, project)

  fun Harness.request(
    left: Duration = 120.seconds,
    gate: SendGate = SendGate(),
    onProgress: (source: () -> String) -> Unit = {},
  ) = QuickChatRequest(context, file, null, clock.nanoTime() + left.inWholeNanoseconds, gate, onProgress)

  val firstUpdate = BindingUpdate(INSTANCE, PROJECT_PREFLIGHT, THREAD, projectChanged = false)
  val preflightOnly = BindingUpdate(INSTANCE, PROJECT_PREFLIGHT, null, projectChanged = false)

  fun stubAsk(answer: () -> AskResponse) {
    every { api.ask(any(), any(), any(), any(), any(), any(), any()) } answers { answer() }
  }

  fun stubMessages(content: String = "The answer", before: () -> Unit = {}) {
    every { api.messages(any(), any(), any(), any()) } answers {
      before()
      listOf(AiMessageNode(REQUEST_ID, "ASSISTANT", content, emptyList(), "t"))
    }
  }

  fun captureLog(): MutableList<String> {
    val log = mockk<ILog>(relaxUnitFun = true)
    val messages = Collections.synchronizedList(mutableListOf<String>())
    every { log.info(capture(messages)) } returns Unit
    every { log.warn(capture(messages)) } returns Unit
    every { log.error(capture(messages)) } returns Unit
    every { Platform.getLog(any<Bundle>()) } returns log
    return messages
  }

  beforeEach {
    clearMocks(api)
    every { api.version(any(), any()) } returns "17.10.0"
    every { api.project(any(), any(), any()) } returns ProjectInfo(GID, true)
    stubAsk { AskResponse(REQUEST_ID, emptyList(), THREAD) }
    stubMessages()
  }

  it("opens the stream before aiAction with the same clientSubscriptionId and closes it after the poll") {
    val streams = FakeStreams.opened()
    val h = harness(streams)
    var callsAtAsk = -1
    stubAsk {
      callsAtAsk = streams.calls
      streams.opened!!.stopReason shouldBe null // still open while aiAction runs
      AskResponse(REQUEST_ID, emptyList(), THREAD)
    }
    h.service.ask(h.request()) shouldBe QuickChatOutcome.Answered("The answer", firstUpdate)
    callsAtAsk shouldBe 1
    streams.calls shouldBe 1
    streams.lastSubscriptionId shouldBe "sub-1"
    verify { api.ask(any(), QUESTION, currentFile, GID, null, "sub-1", any()) }
    streams.opened!!.stopReason shouldBe CableStop.CLOSED_BY_CLIENT
  }

  it("waits at most SUBSCRIBE_WAIT, and less when the deadline is nearer") {
    val streams = FakeStreams.opened()
    val h = harness(streams)
    h.service.ask(h.request(left = 120.seconds))
    streams.lastWait shouldBe QuickChatStreamLimits.SUBSCRIBE_WAIT
    h.service.ask(h.request(left = 1.seconds))
    streams.lastWait shouldBe 1.seconds
  }

  it("confirms the aiAction requestId on the stream so its chunks become progress") {
    val streams = FakeStreams.opened()
    val h = harness(streams)
    stubMessages { streams.opened!!.onChannelMessage(chunk(1, "Hel")) }
    val seen = Collections.synchronizedList(mutableListOf<String>())
    val outcome = h.service.ask(h.request(onProgress = { source -> seen += source() }))
    outcome shouldBe QuickChatOutcome.Answered("The answer", firstUpdate)
    seen shouldContain "Hel"
  }

  it("shows chunks that arrived before the requestId was known once it is confirmed") {
    val streams = FakeStreams.opened()
    val h = harness(streams)
    stubAsk {
      streams.opened!!.onChannelMessage(chunk(1, "Early"))
      AskResponse(REQUEST_ID, emptyList(), THREAD)
    }
    val seen = Collections.synchronizedList(mutableListOf<String>())
    h.service.ask(h.request(onProgress = { source -> seen += source() })) shouldBe
      QuickChatOutcome.Answered("The answer", firstUpdate)
    seen shouldContain "Early"
  }

  it("the stream's final message does not end the send: the poll decides (A-S3)") {
    val streams = FakeStreams.opened()
    val h = harness(streams)
    stubMessages("POLLED") {
      streams.opened!!.onChannelMessage(
        channelMessage("""{"requestId":"$REQUEST_ID","role":"ASSISTANT","content":"STREAM-FINAL"}"""),
      )
    }
    h.service.ask(h.request()) shouldBe QuickChatOutcome.Answered("POLLED", firstUpdate)
    streams.opened!!.finalReceived shouldBe true
  }

  it("an errors final on the stream changes nothing: the poll's answer wins") {
    val streams = FakeStreams.opened()
    val h = harness(streams)
    stubMessages {
      streams.opened!!.onChannelMessage(
        channelMessage("""{"requestId":"$REQUEST_ID","role":"ASSISTANT","content":null,"errors":["M3006"]}"""),
      )
    }
    h.service.ask(h.request()) shouldBe QuickChatOutcome.Answered("The answer", firstUpdate)
  }

  StreamOpenFailure.entries.forEach { reason ->
    it("a stream that fails to open ($reason) leaves the send exactly as without streaming (A-S1)") {
      val withFailure = harness(FakeStreams.failed(reason))
      val gateA = SendGate()
      val outcomeA = withFailure.service.ask(withFailure.request(gate = gateA))
      val without = harness(null)
      val gateB = SendGate()
      val outcomeB = without.service.ask(without.request(gate = gateB))
      outcomeA shouldBe outcomeB
      outcomeA shouldBe QuickChatOutcome.Answered("The answer", firstUpdate)
      gateA.state shouldBe gateB.state
    }
  }

  it("a stream that dies mid-answer leaves the poll to deliver the answer") {
    val streams = FakeStreams.opened()
    val h = harness(streams)
    stubMessages {
      streams.opened!!.onChannelMessage(chunk(1, "Hel"))
      streams.opened!!.client.stop(CableStop.ERROR)
    }
    h.service.ask(h.request()) shouldBe QuickChatOutcome.Answered("The answer", firstUpdate)
    streams.opened!!.stopReason shouldBe CableStop.ERROR // the first reason wins; close is a no-op
  }

  it("closes the stream when the gate refuses at w4 (returns null)") {
    val streams = FakeStreams.opened()
    val h = harness(streams)
    val gate = SendGate()
    gate.closeIfOpen()
    h.service.ask(h.request(gate = gate)) shouldBe null
    streams.opened!!.stopReason shouldBe CableStop.CLOSED_BY_CLIENT
    verify(exactly = 0) { api.ask(any(), any(), any(), any(), any(), any(), any()) }
  }

  it("closes the stream when aiAction is rejected, without confirming its requestId") {
    val streams = FakeStreams.opened()
    val h = harness(streams)
    stubAsk {
      streams.opened!!.onChannelMessage(chunk(1, "Held"))
      AskResponse(REQUEST_ID, listOf("rejected"), THREAD)
    }
    h.service.ask(h.request()) shouldBe QuickChatOutcome.ServerRejected(listOf("rejected"), firstUpdate)
    streams.opened!!.stopReason shouldBe CableStop.CLOSED_BY_CLIENT
    streams.opened!!.displayText() shouldBe "" // never confirmed: the held chunk stays hidden
  }

  it("closes the stream when aiAction throws, classified as without streaming") {
    stubAsk { throw IOException("broken") }
    val without = harness(null)
    val expected = without.service.ask(without.request())
    val streams = FakeStreams.opened()
    val h = harness(streams)
    h.service.ask(h.request()) shouldBe expected
    expected shouldBe QuickChatOutcome.MaybeSent(preflightOnly)
    streams.opened!!.stopReason shouldBe CableStop.CLOSED_BY_CLIENT
  }

  it("when s1 uses up the deadline, sends nothing and times out before send") {
    lateinit var h: Harness
    val streams = FakeStreams {
      h.clock.advance(121.seconds)
      StreamOpenResult.Failed(StreamOpenFailure.TIMED_OUT)
    }
    h = harness(streams)
    val gate = SendGate()
    h.service.ask(h.request(gate = gate)) shouldBe QuickChatOutcome.TimedOut(beforeSend = true, update = preflightOnly)
    verify(exactly = 0) { api.ask(any(), any(), any(), any(), any(), any(), any()) }
    gate.state shouldBe SendGate.State.Open
  }

  it("opens no stream when no time is left for it") {
    val streams = FakeStreams.opened()
    lateinit var h: Harness
    every { api.project(any(), any(), any()) } answers {
      h.clock.advance(120.seconds) // preflight uses up the whole budget
      ProjectInfo(GID, true)
    }
    h = harness(streams)
    h.service.ask(h.request()) shouldBe QuickChatOutcome.TimedOut(beforeSend = true, update = preflightOnly)
    streams.calls shouldBe 0
  }

  it("cancelling during s1 propagates, and nothing is sent") {
    val entered = CompletableDeferred<Unit>()
    val never = CompletableDeferred<Unit>()
    val streams = FakeStreams {
      entered.complete(Unit)
      never.await()
      StreamOpenResult.Failed(StreamOpenFailure.TIMED_OUT)
    }
    val h = harness(streams)
    coroutineScope {
      val job = async { h.service.ask(h.request()) }
      withTimeout(5.seconds) { entered.await() }
      job.cancel()
      shouldThrow<CancellationException> { job.await() }
      job.isCancelled shouldBe true
    }
    verify(exactly = 0) { api.ask(any(), any(), any(), any(), any(), any(), any()) }
  }

  it("cancelling after the stream opened but before aiAction closes the stream and propagates") {
    val streams = FakeStreams { onProgress ->
      // Cancel this send's own coroutine just as the open returns.
      currentCoroutineContext().job.cancel()
      StreamOpenResult.Opened(AiCompletionStream("id", onProgress))
    }
    val h = harness(streams)
    var thrown: Throwable? = null
    coroutineScope {
      val job = async {
        try {
          h.service.ask(h.request())
        } catch (e: CancellationException) {
          thrown = e
          throw e
        }
      }
      job.join()
      job.isCancelled shouldBe true
    }
    thrown.shouldBeInstanceOf<CancellationException>()
    streams.opened!!.stopReason shouldBe CableStop.CLOSED_BY_CLIENT
    verify(exactly = 0) { api.ask(any(), any(), any(), any(), any(), any(), any()) }
  }

  it("cancelling while aiAction blocks closes the stream") {
    val entered = CountDownLatch(1)
    val never = CountDownLatch(1)
    stubAsk {
      entered.countDown()
      never.await()
      AskResponse(REQUEST_ID, emptyList(), THREAD)
    }
    val streams = FakeStreams.opened()
    val h = harness(streams)
    val background = BackgroundScope()
    try {
      val job = background.scope.async { h.service.ask(h.request()) }
      entered.await(5, TimeUnit.SECONDS) shouldBe true
      job.cancel()
      withTimeout(5.seconds) { job.join() }
      job.isCancelled shouldBe true
    } finally {
      never.countDown()
      background.close()
    }
    streams.opened!!.stopReason shouldBe CableStop.CLOSED_BY_CLIENT
  }

  it("a stream open that throws is treated as no stream") {
    val throwing = harness(FakeStreams { throw IllegalStateException("x") })
    val without = harness(null)
    val outcome = throwing.service.ask(throwing.request())
    outcome shouldBe without.service.ask(without.request())
    outcome shouldBe QuickChatOutcome.Answered("The answer", firstUpdate)
  }

  it("a stream open that throws CancellationException propagates it") {
    val h = harness(FakeStreams { throw CancellationException("stop") })
    shouldThrow<CancellationException> { h.service.ask(h.request()) }
    verify(exactly = 0) { api.ask(any(), any(), any(), any(), any(), any(), any()) }
  }

  it("logs one stream summary line with counts only (no text, token or user id)") {
    val messages = captureLog()
    val streams = FakeStreams.opened()
    val h = harness(streams)
    stubMessages { streams.opened!!.onChannelMessage(chunk(1, "SECRET-CHUNK")) }
    h.service.ask(h.request(onProgress = { it() }))
    val rejected = harness(FakeStreams.failed(StreamOpenFailure.REJECTED))
    rejected.service.ask(rejected.request())
    val throwing = harness(FakeStreams { throw IllegalStateException("SECRET-QUESTION") })
    throwing.service.ask(throwing.request())
    val summaries = messages.filter { it.startsWith("Quick Chat stream ended") }
    summaries shouldBe listOf(
      "Quick Chat stream ended: open=CONFIRMED stop=CLOSED_BY_CLIENT chunks=1 seriesResets=0 final=false " +
        "requestId=$REQUEST_ID",
      "Quick Chat stream ended: open=REJECTED stop=none chunks=0 seriesResets=0 final=false requestId=$REQUEST_ID",
      "Quick Chat stream ended: open=OPEN_THREW:java.lang.IllegalStateException stop=none chunks=0 " +
        "seriesResets=0 final=false requestId=$REQUEST_ID",
    )
    messages.none { "SECRET" in it || "tok" in it || "SecretFile" in it || "User/" in it } shouldBe true
    messages.none { "Quick Chat stream stopped" in it } shouldBe true // the client's normal stop is not logged
  }

  it("logs no stream summary when there is no stream to try") {
    val messages = captureLog()
    val without = harness(null)
    without.service.ask(without.request())
    val streams = FakeStreams.opened()
    lateinit var h: Harness
    every { api.project(any(), any(), any()) } answers {
      h.clock.advance(120.seconds)
      ProjectInfo(GID, true)
    }
    h = harness(streams)
    h.service.ask(h.request())
    messages.none { it.startsWith("Quick Chat stream ended") } shouldBe true
  }

  it("progress may come from two threads at once and after close without breaking the send") {
    val streams = FakeStreams.opened()
    val h = harness(streams)
    val count = 200
    stubMessages {
      val stream = streams.opened!!
      val other = thread {
        repeat(count) { stream.onChannelMessage(chunk(it + 1, "a")) }
      }
      repeat(count) { stream.onChannelMessage(chunk(it + 1, "a", requestId = "req-other")) }
      repeat(count) { stream.onChannelMessage(chunk(count + it + 1, "b")) }
      other.join(TimeUnit.SECONDS.toMillis(30))
    }
    val seen = Collections.synchronizedList(mutableListOf<String>())
    val outcome = h.service.ask(
      h.request(
        onProgress = { source ->
          Thread.sleep(1)
          seen += source()
        },
      ),
    )
    outcome shouldBe QuickChatOutcome.Answered("The answer", firstUpdate)
    seen.isNotEmpty() shouldBe true
    // A late call after close (a transport callback racing the stop) still works and reads the text.
    streams.lastOnProgress!!.invoke()
    seen.last().length shouldBe 2 * count
    streams.opened!!.stopReason shouldBe CableStop.CLOSED_BY_CLIENT
  }
})
