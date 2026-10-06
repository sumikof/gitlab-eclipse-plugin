package com.gitlab.eclipse.chat.quickchat

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture

class JdkCableSocketFactoryTest : DescribeSpec({
  it("connects through the shared HttpClient with the ActionCable subprotocol, Bearer and Origin only") {
    val builder = mockk<WebSocket.Builder>()
    every { builder.subprotocols(any(), *anyVararg()) } returns builder
    every { builder.header(any(), any()) } returns builder
    every { builder.connectTimeout(any()) } returns builder
    every { builder.buildAsync(any(), any()) } returns CompletableFuture()
    val client = mockk<HttpClient>()
    every { client.newWebSocketBuilder() } returns builder
    var supplied = 0
    val factory = JdkCableSocketFactory {
      supplied++
      client
    }
    val endpoint = CableEndpoint.of("https://gitlab.com")

    factory.connect(
      endpoint,
      "tok",
      object : CableListener {
        override fun onOpen(socket: CableSocket) = Unit
        override fun onText(data: CharSequence, last: Boolean) = Unit
        override fun onBinary(size: Int, last: Boolean) = Unit
        override fun onClosed() = Unit
        override fun onError(error: Throwable) = Unit
      },
    )
    factory.connect(endpoint, "tok", ActionCableClient("x", {}))

    supplied shouldBe 2
    verify { builder.subprotocols("actioncable-v1-json") }
    verify { builder.header("Authorization", "Bearer tok") }
    verify { builder.header("Origin", "https://gitlab.com") }
    verify(exactly = 0) { builder.header("User-Agent", any()) }
    verify(exactly = 2) { builder.connectTimeout(QuickChatStreamLimits.HANDSHAKE_TIMEOUT) }
    QuickChatStreamLimits.HANDSHAKE_TIMEOUT shouldBe java.time.Duration.ofSeconds(30)
    verify { builder.buildAsync(URI("wss://gitlab.com/-/cable"), any()) }
  }
  it("adapts the JDK listener without the default request(1) and forwards every callback") {
    val captured = slot<WebSocket.Listener>()
    val builder = mockk<WebSocket.Builder>()
    every { builder.subprotocols(any(), *anyVararg()) } returns builder
    every { builder.header(any(), any()) } returns builder
    every { builder.connectTimeout(any()) } returns builder
    every { builder.buildAsync(any(), capture(captured)) } returns CompletableFuture()
    val client = mockk<HttpClient>()
    every { client.newWebSocketBuilder() } returns builder
    val events = mutableListOf<String>()
    var opened: CableSocket? = null
    JdkCableSocketFactory { client }.connect(
      CableEndpoint.of("https://gitlab.com"),
      "tok",
      object : CableListener {
        override fun onOpen(socket: CableSocket) {
          opened = socket
          events += "open"
        }
        override fun onText(data: CharSequence, last: Boolean) {
          events += "text:$data:$last"
        }
        override fun onBinary(size: Int, last: Boolean) {
          events += "binary:$size:$last"
        }
        override fun onClosed() {
          events += "closed"
        }
        override fun onError(error: Throwable) {
          events += "error:${error.javaClass.simpleName}"
        }
      },
    )
    val ws = mockk<WebSocket>(relaxed = true)
    val adapter = captured.captured

    adapter.onOpen(ws)
    verify(exactly = 0) { ws.request(any()) }
    adapter.onText(ws, "abc", false) shouldBe null
    val buffer = java.nio.ByteBuffer.allocate(16).apply { position(4) }
    adapter.onBinary(ws, buffer, true) shouldBe null
    verify(exactly = 0) { ws.request(any()) }
    adapter.onClose(ws, 1000, "bye") shouldBe null
    adapter.onError(ws, java.io.IOException("x"))
    opened!!.sendText("hi")
    opened!!.request(1)
    opened!!.abort()

    events shouldBe listOf("open", "text:abc:false", "binary:12:true", "closed", "error:IOException")
    verify { ws.sendText("hi", true) }
    verify { ws.request(1) }
    verify { ws.abort() }
  }

  describe("handshake cancellation") {
    fun connectWith(source: CompletableFuture<WebSocket>): CompletableFuture<CableSocket> {
      val builder = mockk<WebSocket.Builder>()
      every { builder.subprotocols(any(), *anyVararg()) } returns builder
      every { builder.header(any(), any()) } returns builder
      every { builder.connectTimeout(any()) } returns builder
      every { builder.buildAsync(any(), any()) } returns source
      val client = mockk<HttpClient>()
      every { client.newWebSocketBuilder() } returns builder
      return JdkCableSocketFactory { client }.connect(
        CableEndpoint.of("https://gitlab.com"),
        "tok",
        ActionCableClient("x", {}),
      )
    }

    // The JDK's buildAsync future is a dependent stage (send().thenApply(newWebSocket)): cancelling
    // it cannot stop the exchange and would drop a late-opened connection unclosed. So the source is
    // never cancelled; a WebSocket arriving after the caller gave up is aborted instead.
    it("does not cancel the source when the returned future is cancelled") {
      val source = CompletableFuture<WebSocket>()
      val returned = connectWith(source)
      returned.cancel(true)
      source.isCancelled shouldBe false
      source.isDone shouldBe false
    }
    it("does not cancel the source when the returned future fails with a cancellation") {
      val source = CompletableFuture<WebSocket>()
      val returned = connectWith(source)
      returned.completeExceptionally(java.util.concurrent.CancellationException("gave up"))
      source.isCancelled shouldBe false
    }
    it("aborts exactly once a WebSocket that arrives after the returned future was cancelled") {
      val source = CompletableFuture<WebSocket>()
      val returned = connectWith(source)
      returned.cancel(true)
      val ws = mockk<WebSocket>(relaxed = true)
      source.complete(ws)
      verify(exactly = 1) { ws.abort() }
    }
    it("does nothing when the source fails after the returned future was cancelled") {
      val source = CompletableFuture<WebSocket>()
      val returned = connectWith(source)
      returned.cancel(true)
      source.completeExceptionally(java.io.IOException("x"))
      returned.isCancelled shouldBe true
    }
    it("maps a normal completion to a CableSocket without aborting") {
      val source = CompletableFuture<WebSocket>()
      val returned = connectWith(source)
      val ws = mockk<WebSocket>(relaxed = true)
      source.complete(ws)
      returned.isDone shouldBe true
      returned.get().sendText("hi")
      verify { ws.sendText("hi", true) }
      verify(exactly = 0) { ws.abort() }
      source.isCancelled shouldBe false
    }
    it("propagates a failed handshake without cancelling") {
      val source = CompletableFuture<WebSocket>()
      val returned = connectWith(source)
      source.completeExceptionally(java.io.IOException("x"))
      returned.isCompletedExceptionally shouldBe true
      returned.isCancelled shouldBe false
    }
  }
})
