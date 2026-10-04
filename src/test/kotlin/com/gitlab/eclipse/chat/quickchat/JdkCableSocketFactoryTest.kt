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
    verify { builder.buildAsync(URI("wss://gitlab.com/-/cable"), any()) }
  }
  it("adapts the JDK listener without the default request(1) and forwards every callback") {
    val captured = slot<WebSocket.Listener>()
    val builder = mockk<WebSocket.Builder>()
    every { builder.subprotocols(any(), *anyVararg()) } returns builder
    every { builder.header(any(), any()) } returns builder
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
})
