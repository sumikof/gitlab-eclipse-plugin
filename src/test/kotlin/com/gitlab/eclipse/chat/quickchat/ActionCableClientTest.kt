package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.Platform
import org.osgi.framework.Bundle

@OptIn(ExperimentalCoroutinesApi::class)
class ActionCableClientTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  class FakeSocket : CableSocket {
    val sent = mutableListOf<String>()
    var requested = 0L
    var aborted = 0
    override fun sendText(text: String) {
      sent += text
    }

    override fun request(n: Long) {
      requested += n
    }

    override fun abort() {
      aborted++
    }
  }
  val id = ActionCableClient.identifier("subscription q { x }", """{"a":1}""")
  fun quoted(s: String) = com.google.gson.Gson().toJson(s)
  fun msg(json: String, client: ActionCableClient) = client.onText(json, true)
  fun open(
    onMessage: (JsonObject) -> Unit = {},
    frames: Int = 20_000,
    chars: Long = 1_000_000,
    frame: Int = 1_000,
  ): Pair<ActionCableClient, FakeSocket> {
    val c = ActionCableClient(id, onMessage, frame, chars, frames)
    val s = FakeSocket()
    c.onOpen(s)
    return c to s
  }

  it("builds the identifier in the documented key order") {
    JsonParser.parseString(id).asJsonObject.keySet().toList() shouldBe
      listOf("channel", "query", "variables", "operationName")
    JsonParser.parseString(id).asJsonObject["channel"].asString shouldBe "GraphqlChannel"
  }
  it("keeps variables as a JSON string value and names the operation") {
    val parsed = JsonParser.parseString(id).asJsonObject
    parsed["query"].asString shouldBe "subscription q { x }"
    parsed["variables"].asString shouldBe """{"a":1}"""
    parsed["operationName"].asString shouldBe "aiCompletionResponse"
  }
  it("subscribes once after welcome and confirms on a matching confirm_subscription") {
    val (c, s) = open()
    msg("""{"type":"welcome"}""", c)
    msg("""{"type":"welcome"}""", c)
    s.sent shouldBe listOf("""{"command":"subscribe","identifier":${quoted(id)}}""")
    msg("""{"type":"confirm_subscription","identifier":${quoted("other")}}""", c)
    c.confirmation.isCompleted shouldBe false
    msg("""{"type":"confirm_subscription","identifier":${quoted(id)}}""", c)
    c.confirmation.getCompleted() shouldBe true
  }
  it("stops on reject, disconnect, close and error, aborting the socket once") {
    listOf<Pair<String, (ActionCableClient) -> Unit>>(
      "REJECTED" to { msg("""{"type":"reject_subscription","identifier":${quoted(id)}}""", it) },
      "DISCONNECTED" to { msg("""{"type":"disconnect","reason":"unauthorized","reconnect":false}""", it) },
      "CLOSED_BY_SERVER" to { it.onClosed() },
      "ERROR" to { it.onError(java.io.IOException("x")) },
    ).forEach { (reason, act) ->
      val (c, s) = open()
      act(c)
      c.stopReason shouldBe CableStop.valueOf(reason)
      c.confirmation.getCompleted() shouldBe false
      c.stop(CableStop.CLOSED_BY_CLIENT)
      c.stopReason shouldBe CableStop.valueOf(reason)
      s.aborted shouldBe 1
    }
  }
  it("ignores a reject_subscription of another identifier") {
    val (c, _) = open()
    msg("""{"type":"reject_subscription","identifier":${quoted("other")}}""", c)
    c.stopReason shouldBe null
  }
  it("delivers only messages of its own identifier") {
    val got = mutableListOf<JsonObject>()
    val (c, _) = open({ got += it })
    msg("""{"identifier":${quoted("other")},"message":{"more":true}}""", c)
    msg(
      """{"identifier":${quoted(id)},"message":{"result":{"data":{"aiCompletionResponse":null}},"more":true}}""",
      c,
    )
    msg("""{"identifier":${quoted(id)},"message":"not an object"}""", c)
    got.size shouldBe 1
  }
  it("joins a frame split over several onText calls") {
    val (c, s) = open()
    c.onText("""{"type":"wel""", false)
    c.onText("""come"}""", true)
    s.sent.size shouldBe 1
  }
  it("ignores pings and malformed JSON but counts them") {
    val (c, s) = open(frames = 3)
    msg("""{"type":"ping","message":1}""", c)
    msg("""not json""", c)
    c.stopReason shouldBe null
    msg("""{"type":"ping","message":2}""", c)
    msg("""{"type":"ping","message":3}""", c)
    c.stopReason shouldBe CableStop.OVERFLOW
    s.aborted shouldBe 1
  }
  it("stops on the cumulative char limit even if every frame is small and dropped") {
    val (c, _) = open(chars = 100)
    repeat(20) { msg("""{"type":"ping"}""", c) }
    c.stopReason shouldBe CableStop.OVERFLOW
  }
  it("counts binary frames toward the frame limit without parsing them") {
    val (c, s) = open(frames = 3)
    repeat(3) { c.onBinary(1, true) }
    c.stopReason shouldBe null
    s.requested shouldBe 4
    s.sent.shouldBeEmpty()
    c.onBinary(1, true)
    c.stopReason shouldBe CableStop.OVERFLOW
    s.aborted shouldBe 1
    s.requested shouldBe 4
  }
  it("stops on endless empty non-final text parts") {
    val (c, s) = open(frames = 3)
    repeat(3) { c.onText("", false) }
    c.stopReason shouldBe null
    c.onText("", false)
    c.stopReason shouldBe CableStop.OVERFLOW
    s.aborted shouldBe 1
    s.requested shouldBe 4
  }
  it("stops on endless empty non-final binary parts") {
    val (c, s) = open(frames = 3)
    repeat(3) { c.onBinary(0, false) }
    c.stopReason shouldBe null
    c.onBinary(0, false)
    c.stopReason shouldBe CableStop.OVERFLOW
    s.aborted shouldBe 1
    s.requested shouldBe 4
  }
  it("counts binary bytes toward the char limit, partial parts included") {
    val (c, _) = open(chars = 100)
    c.onBinary(60, false)
    c.stopReason shouldBe null
    c.onBinary(41, false)
    c.stopReason shouldBe CableStop.OVERFLOW
  }
  it("counts binary and text together") {
    val (c, _) = open(chars = 20)
    c.onBinary(10, true)
    msg("""{"type":"ping"}""", c)
    c.stopReason shouldBe CableStop.OVERFLOW
  }
  it("stops on an oversized single frame while still joining it") {
    val (c, _) = open(frame = 10)
    c.onText("12345", false)
    c.onText("678901", false)
    c.stopReason shouldBe CableStop.OVERFLOW
  }
  it("requests the next frame after each handled frame, not after stopping") {
    val (c, s) = open()
    s.requested shouldBe 1
    msg("""{"type":"ping"}""", c)
    s.requested shouldBe 2
    c.stop(CableStop.CLOSED_BY_CLIENT)
    msg("""{"type":"ping"}""", c)
    s.requested shouldBe 2
  }
  it("stops with LISTENER_FAILED and never throws when onMessage throws") {
    val (c, s) = open({ throw IllegalStateException("boom") })
    msg("""{"identifier":${quoted(id)},"message":{"more":true}}""", c)
    c.stopReason shouldBe CableStop.LISTENER_FAILED
    s.aborted shouldBe 1
  }
  it("never throws when the socket itself throws") {
    val c = ActionCableClient(id, {})
    val s = object : CableSocket {
      override fun sendText(text: String) = error("send")
      override fun request(n: Long) = error("request")
      override fun abort() = error("abort")
    }
    c.onOpen(s)
    msg("""{"type":"welcome"}""", c)
    c.stop(CableStop.CLOSED_BY_CLIENT)
    c.stopReason shouldBe CableStop.ERROR
  }
  it("aborts a socket that opens after the client was stopped") {
    val c = ActionCableClient(id, {})
    c.stop(CableStop.CLOSED_BY_CLIENT)
    val s = FakeSocket()
    c.onOpen(s)
    s.aborted shouldBe 1
    s.sent.shouldBeEmpty()
    c.confirmation.getCompleted() shouldBe false
  }
  it("a client-requested stop is not logged (the send's stream summary covers it)") {
    val log = mockk<ILog>(relaxUnitFun = true)
    val messages = mutableListOf<String>()
    every { log.info(capture(messages)) } returns Unit
    every { Platform.getLog(any<Bundle>()) } returns log
    val (client, _) = open()
    client.stop(CableStop.CLOSED_BY_CLIENT)
    messages.none { "Quick Chat stream stopped" in it } shouldBe true
    val (client2, _) = open()
    client2.stop(CableStop.OVERFLOW)
    messages.any { "Quick Chat stream stopped: OVERFLOW" in it } shouldBe true
  }
})
