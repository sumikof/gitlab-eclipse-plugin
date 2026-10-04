package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.Platform
import org.osgi.framework.Bundle
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [AiCompletionStreamOpener] and [AiCompletionStream] (design `quick-chat-streaming`
 * §9.1 s1, §14, §15.1, §19). The socket factory is a fake the test drives frame by frame; time is the
 * virtual time of `runTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiCompletionStreamTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  class FakeSocket : CableSocket {
    val sent = mutableListOf<String>()
    var aborted = 0

    override fun sendText(text: String) {
      sent += text
    }

    override fun request(n: Long) = Unit

    override fun abort() {
      aborted++
    }
  }

  class FakeSockets : CableSocketFactory {
    val future = CompletableFuture<CableSocket>()
    var listener: CableListener? = null
    var token: String? = null
    var endpoint: CableEndpoint? = null
    var socket: FakeSocket? = null
    var connectFailure: RuntimeException? = null

    override fun connect(endpoint: CableEndpoint, token: String, listener: CableListener): CompletableFuture<CableSocket> {
      connectFailure?.let { throw it }
      this.listener = listener
      this.token = token
      this.endpoint = endpoint
      return future
    }

    val client: ActionCableClient get() = listener as ActionCableClient

    /** The handshake succeeds: the listener sees the socket first, as the JDK does. */
    fun accept(): FakeSocket = FakeSocket().also {
      socket = it
      listener!!.onOpen(it)
      future.complete(it)
    }

    fun text(json: String) = listener!!.onText(json, true)

    /** The identifier this client subscribed with (from its `subscribe` command). */
    fun identifier(): String = JsonParser.parseString(socket!!.sent.first()).asJsonObject["identifier"].asString

    fun welcome() = text("""{"type":"welcome"}""")

    fun confirm() {
      welcome()
      text("""{"type":"confirm_subscription","identifier":${Gson().toJson(identifier())}}""")
    }

    fun reject() {
      welcome()
      text("""{"type":"reject_subscription","identifier":${Gson().toJson(identifier())}}""")
    }

    fun response(fields: JsonObject): JsonObject = JsonObject().apply {
      add("result", JsonObject().apply { add("data", JsonObject().apply { add("aiCompletionResponse", fields) }) })
      addProperty("more", true)
    }

    fun frame(message: JsonObject) = text(
      JsonObject().apply {
        addProperty("identifier", identifier())
        add("message", message)
      }.toString(),
    )

    fun fields(requestId: String, chunkId: Int?, content: String): JsonObject = JsonObject().apply {
      addProperty("requestId", requestId)
      addProperty("role", "ASSISTANT")
      addProperty("content", content)
      add("errors", com.google.gson.JsonArray())
      chunkId?.let { addProperty("chunkId", it) }
    }

    fun chunk(n: Int, content: String, requestId: String = "req-1") = frame(response(fields(requestId, n, content)))
  }

  val userId = "gid://gitlab/User/7"
  val connection: ConnectionSnapshot = snapshot()

  class Harness(
    val api: QuickChatApi = mockk(),
    val sockets: FakeSockets = FakeSockets(),
    val clock: FakeClock = FakeClock(),
  ) {
    val opener = AiCompletionStreamOpener(api, sockets, clock)
    var progress = 0
  }

  /** Starts `open` and runs it up to its first suspension. */
  fun TestScope.start(
    h: Harness,
    waitLimit: Duration = 3.seconds,
    onProgress: () -> Unit = { h.progress++ },
  ) = async(start = CoroutineStart.UNDISPATCHED) { h.opener.open(connection, "sub-1", waitLimit, onProgress) }
    .also { runCurrent() }

  suspend fun TestScope.opened(h: Harness, onProgress: () -> Unit = { h.progress++ }): AiCompletionStream {
    every { h.api.currentUserId(any(), any()) } returns userId
    val result = start(h, onProgress = onProgress)
    h.sockets.accept()
    h.sockets.confirm()
    return result.await().shouldBeInstanceOf<StreamOpenResult.Opened>().stream
  }

  it("opens after confirm_subscription, sending the documented variables with the user id") {
    runTest {
      val h = Harness()
      every { h.api.currentUserId(any(), any()) } returns userId
      val result = start(h)
      h.sockets.accept()
      h.sockets.confirm()

      val stream = result.await().shouldBeInstanceOf<StreamOpenResult.Opened>().stream
      stream.stopReason shouldBe null
      h.sockets.token shouldBe "tok"
      h.sockets.endpoint shouldBe CableEndpoint.of(INSTANCE)
      val identifier = JsonParser.parseString(h.sockets.identifier()).asJsonObject
      identifier["variables"].asString shouldBe
        """{"htmlResponse":false,"userId":"gid://gitlab/User/7","aiAction":"CHAT","clientSubscriptionId":"sub-1"}"""
      identifier["query"].asString shouldBe AiCompletionStreamOpener.SUBSCRIPTION_QUERY
      verify { h.api.currentUserId(connection, java.time.Duration.ofSeconds(3)) }
    }
  }

  it("subscribes with exactly the documented subscription fields and arguments") {
    AiCompletionStreamOpener.SUBSCRIPTION_QUERY shouldBe
      "subscription aiCompletionResponse(\$userId: UserID, \$clientSubscriptionId: String, \$aiAction: AiAction, " +
      "\$htmlResponse: Boolean = true) { aiCompletionResponse(userId: \$userId, aiAction: \$aiAction, " +
      "clientSubscriptionId: \$clientSubscriptionId) { id requestId content contentHtml @include(if: \$htmlResponse) " +
      "errors role timestamp type chunkId extras { sources } } }"
  }

  it("fails with TIMED_OUT without querying anything when the wait limit is not positive") {
    runTest {
      val h = Harness()
      start(h, waitLimit = Duration.ZERO).await() shouldBe StreamOpenResult.Failed(StreamOpenFailure.TIMED_OUT)
      verify(exactly = 0) { h.api.currentUserId(any(), any()) }
      h.sockets.listener shouldBe null
    }
  }

  it("fails with NO_USER without connecting when the user id is null") {
    runTest {
      val h = Harness()
      every { h.api.currentUserId(any(), any()) } returns null
      start(h).await() shouldBe StreamOpenResult.Failed(StreamOpenFailure.NO_USER)
      h.sockets.listener shouldBe null
    }
  }

  it("fails with NO_USER without connecting when the user id is empty") {
    runTest {
      val h = Harness()
      every { h.api.currentUserId(any(), any()) } returns ""
      start(h).await() shouldBe StreamOpenResult.Failed(StreamOpenFailure.NO_USER)
      h.sockets.listener shouldBe null
    }
  }

  it("fails with USER_QUERY_FAILED on an exception, naming only its class") {
    runTest {
      val h = Harness()
      every { h.api.currentUserId(any(), any()) } throws IOException("secret detail tok")
      start(h).await() shouldBe
        StreamOpenResult.Failed(StreamOpenFailure.USER_QUERY_FAILED, "java.io.IOException")
      h.sockets.listener shouldBe null
    }
  }

  it("fails with TIMED_OUT without connecting when the user query used up the wait") {
    runTest {
      val h = Harness()
      every { h.api.currentUserId(any(), any()) } answers {
        h.clock.advanceNanos(3.seconds.inWholeNanoseconds)
        userId
      }
      start(h).await() shouldBe StreamOpenResult.Failed(StreamOpenFailure.TIMED_OUT)
      h.sockets.listener shouldBe null
    }
  }

  it("fails with INVALID_ENDPOINT without connecting when the instance URL is not http(s)") {
    runTest {
      val h = Harness()
      every { h.api.currentUserId(any(), any()) } returns userId
      val result = async { h.opener.open(snapshot("ftp://gitlab.example.com"), "sub-1", 3.seconds) {} }
      result.await() shouldBe StreamOpenResult.Failed(StreamOpenFailure.INVALID_ENDPOINT)
      h.sockets.listener shouldBe null
    }
  }

  it("fails with CONNECT_FAILED when connect itself throws") {
    runTest {
      val h = Harness()
      every { h.api.currentUserId(any(), any()) } returns userId
      h.sockets.connectFailure = IllegalStateException("x")
      start(h).await() shouldBe
        StreamOpenResult.Failed(StreamOpenFailure.CONNECT_FAILED, "java.lang.IllegalStateException")
    }
  }

  it("fails with CONNECT_FAILED when the handshake future fails, and aborts nothing it never had") {
    runTest {
      val h = Harness()
      every { h.api.currentUserId(any(), any()) } returns userId
      val result = start(h)
      h.sockets.future.completeExceptionally(CompletionException(IOException("handshake")))

      result.await() shouldBe StreamOpenResult.Failed(StreamOpenFailure.CONNECT_FAILED, "java.io.IOException")
      h.sockets.socket shouldBe null
      h.sockets.client.stopReason shouldBe CableStop.CLOSED_BY_CLIENT
    }
  }

  it("fails with REJECTED on reject_subscription and aborts the socket") {
    runTest {
      val h = Harness()
      every { h.api.currentUserId(any(), any()) } returns userId
      val result = start(h)
      val socket = h.sockets.accept()
      h.sockets.reject()

      result.await() shouldBe StreamOpenResult.Failed(StreamOpenFailure.REJECTED)
      socket.aborted shouldBe 1
      h.sockets.client.stopReason shouldBe CableStop.REJECTED
    }
  }

  it("fails with STOPPED when the server closes before confirming, and aborts the socket") {
    runTest {
      val h = Harness()
      every { h.api.currentUserId(any(), any()) } returns userId
      val result = start(h)
      val socket = h.sockets.accept()
      h.sockets.welcome()
      h.sockets.listener!!.onClosed()

      result.await() shouldBe StreamOpenResult.Failed(StreamOpenFailure.STOPPED)
      socket.aborted shouldBe 1
    }
  }

  it("fails with TIMED_OUT when no confirm arrives in time, and a socket opening later is aborted") {
    runTest {
      val h = Harness()
      every { h.api.currentUserId(any(), any()) } returns userId
      val result = start(h, waitLimit = 1.seconds)

      result.await() shouldBe StreamOpenResult.Failed(StreamOpenFailure.TIMED_OUT)
      testScheduler.currentTime shouldBe 1_000L
      h.sockets.client.stopReason shouldBe CableStop.CLOSED_BY_CLIENT
      val late = h.sockets.accept()
      late.aborted shouldBe 1
      late.sent.shouldBeEmpty()
    }
  }

  it("fails with TIMED_OUT and aborts an open socket when confirm never comes") {
    runTest {
      val h = Harness()
      every { h.api.currentUserId(any(), any()) } returns userId
      val result = start(h, waitLimit = 1.seconds)
      val socket = h.sockets.accept()
      h.sockets.welcome()

      result.await() shouldBe StreamOpenResult.Failed(StreamOpenFailure.TIMED_OUT)
      socket.aborted shouldBe 1
    }
  }

  it("aborts the socket and rethrows when the caller is cancelled while waiting for confirm") {
    runTest {
      val h = Harness()
      every { h.api.currentUserId(any(), any()) } returns userId
      var outcome = "running"
      val job = async(start = CoroutineStart.UNDISPATCHED) {
        try {
          h.opener.open(connection, "sub-1", 3.seconds) {}
          outcome = "returned"
        } catch (e: CancellationException) {
          outcome = "rethrown"
          throw e
        }
      }
      runCurrent()
      val socket = h.sockets.accept()
      h.sockets.welcome()
      runCurrent()

      job.cancel()
      runCurrent()
      outcome shouldBe "rethrown"
      socket.aborted shouldBe 1
      h.sockets.client.stopReason shouldBe CableStop.CLOSED_BY_CLIENT
    }
  }

  it("aborts a socket that opens after the caller was cancelled during the handshake") {
    runTest {
      val h = Harness()
      every { h.api.currentUserId(any(), any()) } returns userId
      val job = start(h)
      job.cancel()
      runCurrent()

      h.sockets.client.stopReason shouldBe CableStop.CLOSED_BY_CLIENT
      h.sockets.accept().aborted shouldBe 1
    }
  }

  it("calls onProgress on chunks only after the requestId is confirmed, and displayText follows") {
    runTest {
      val h = Harness()
      val stream = opened(h)
      h.sockets.chunk(1, "Hello ")
      h.sockets.chunk(1, "other", requestId = "req-2")
      h.progress shouldBe 0
      stream.displayText() shouldBe ""

      stream.confirmRequestId("req-1")
      h.progress shouldBe 1
      stream.displayText() shouldBe "Hello "

      h.sockets.chunk(3, "!")
      h.progress shouldBe 1
      h.sockets.chunk(2, "world")
      h.progress shouldBe 2
      stream.displayText() shouldBe "Hello world!"
      stream.chunksAccepted shouldBe 3
      stream.seriesResets shouldBe 0
      stream.finalReceived shouldBe false

      h.sockets.frame(h.sockets.response(h.sockets.fields("req-1", null, "Hello world!")))
      h.progress shouldBe 3
      stream.finalReceived shouldBe true
      stream.stopReason shouldBe null
    }
  }

  it("counts a series reset") {
    runTest {
      val h = Harness()
      val stream = opened(h)
      stream.confirmRequestId("req-1")
      h.sockets.chunk(1, "a")
      h.sockets.chunk(1, "b")
      stream.seriesResets shouldBe 1
      stream.displayText() shouldBe "b"
    }
  }

  it("drops malformed or foreign-shaped responses without stopping") {
    runTest {
      val h = Harness()
      val stream = opened(h)
      stream.confirmRequestId("req-1")
      h.sockets.frame(JsonObject().apply { addProperty("more", true) })
      h.sockets.frame(h.sockets.response(JsonObject().apply { addProperty("chunkId", "not a number") }))
      h.sockets.frame(
        JsonObject().apply { add("result", JsonObject().apply { addProperty("data", "x") }) },
      )
      h.sockets.frame(h.sockets.response(h.sockets.fields("req-1", 1, "ok").apply { addProperty("errors", 5) }))
      stream.stopReason shouldBe null
      h.progress shouldBe 0
      h.sockets.chunk(1, "ok")
      h.progress shouldBe 1
    }
  }

  it("stops with OVERFLOW when the assembler overflows") {
    runTest {
      val h = Harness()
      val stream = opened(h)
      repeat(QuickChatStreamLimits.MAX_PENDING_FRAMES) { h.sockets.chunk(it + 1, "x") }
      stream.stopReason shouldBe null
      h.sockets.chunk(QuickChatStreamLimits.MAX_PENDING_FRAMES + 1, "x")

      stream.stopReason shouldBe CableStop.OVERFLOW
      h.sockets.socket!!.aborted shouldBe 1
      stream.confirmRequestId("req-1")
      h.progress shouldBe 0
    }
  }

  it("stops with LISTENER_FAILED when onProgress throws") {
    runTest {
      val h = Harness()
      val stream = opened(h) { error("boom") }
      stream.confirmRequestId("req-1")
      h.sockets.chunk(1, "a")
      stream.stopReason shouldBe CableStop.LISTENER_FAILED
      h.sockets.socket!!.aborted shouldBe 1
    }
  }

  it("ignores a message delivered after close") {
    runTest {
      val h = Harness()
      val stream = opened(h)
      stream.confirmRequestId("req-1")
      val late = h.sockets.response(h.sockets.fields("req-1", 1, "late"))
      stream.close()
      stream.onChannelMessage(late)
      h.progress shouldBe 0
      stream.displayText() shouldBe ""
      stream.chunksAccepted shouldBe 0
    }
  }

  it("close is idempotent and aborts once") {
    runTest {
      val h = Harness()
      val stream = opened(h)
      stream.close()
      stream.close()
      stream.stopReason shouldBe CableStop.CLOSED_BY_CLIENT
      h.sockets.socket!!.aborted shouldBe 1
    }
  }

  it("never logs the token, the user id or chunk text") {
    val log = mockk<ILog>(relaxUnitFun = true)
    val messages = mutableListOf<String>()
    every { log.info(capture(messages)) } returns Unit
    every { log.warn(capture(messages)) } returns Unit
    every { log.error(capture(messages)) } returns Unit
    every { Platform.getLog(any<Bundle>()) } returns log
    runTest {
      // A failed user query whose message carries secrets.
      val failing = Harness()
      every { failing.api.currentUserId(any(), any()) } throws IOException("tok $userId")
      start(failing).await()
      // An opened stream that receives chunks (its own and foreign ones) and closes.
      val h = Harness()
      val stream = opened(h)
      stream.confirmRequestId("req-1")
      h.sockets.chunk(1, "SECRET-CHUNK")
      repeat(QuickChatStreamLimits.MAX_PENDING_FRAMES + 1) { h.sockets.chunk(it + 2, "SECRET-CHUNK", "req-9") }
      stream.close()
      // A timed-out stream.
      val slow = Harness()
      every { slow.api.currentUserId(any(), any()) } returns userId
      start(slow, waitLimit = 1.seconds).await()
    }
    messages.isNotEmpty() shouldBe true
    messages.none { "tok" in it || "User/7" in it || "SECRET" in it || "sub-1" in it || "gitlab.com" in it } shouldBe
      true
    verify(exactly = 0) { log.log(any()) }
  }
})
