package com.gitlab.eclipse.api

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

private const val CAP = 8

/** Records what the subscriber asks of its upstream. */
private class FakeSubscription : Flow.Subscription {
  var requested = 0L
  var cancelled = false

  override fun request(n: Long) {
    requested += n
  }

  override fun cancel() {
    cancelled = true
  }
}

private fun info(status: Int, headers: Map<String, List<String>> = emptyMap()) =
  object : HttpResponse.ResponseInfo {
    override fun statusCode(): Int = status

    override fun headers(): HttpHeaders = HttpHeaders.of(headers) { _, _ -> true }

    override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
  }

private fun buffers(vararg chunks: String): List<ByteBuffer> = chunks.map { ByteBuffer.wrap(it.toByteArray()) }

/** Drives a subscriber through subscribe → each publish → complete, the way the HTTP client would. */
private fun drive(
  status: Int,
  headers: Map<String, List<String>> = emptyMap(),
  publishes: List<List<ByteBuffer>> = emptyList(),
): Pair<BoundedBytes?, FakeSubscription> {
  val subscriber = BoundedBytesHandler(CAP).apply(info(status, headers))
  val subscription = FakeSubscription()
  subscriber.onSubscribe(subscription)
  publishes.forEach(subscriber::onNext)
  subscriber.onComplete()
  return subscriber.body.toCompletableFuture().get(1, TimeUnit.SECONDS) to subscription
}

class BoundedBytesHandlerTest : DescribeSpec({
  describe("BoundedBytesHandler on a 2xx response") {
    it("returns the exact bytes when under the cap") {
      val (body, subscription) = drive(200, publishes = listOf(buffers("abc", "de")))

      body.shouldBeInstanceOf<BoundedBytes.Ok>().bytes.decodeToString() shouldBe "abcde"
      subscription.cancelled shouldBe false
    }

    it("returns the bytes when the body is exactly the cap") {
      val (body, _) = drive(200, publishes = listOf(buffers("1234"), buffers("5678")))

      body.shouldBeInstanceOf<BoundedBytes.Ok>().bytes.decodeToString() shouldBe "12345678"
    }

    it("returns an empty body as empty bytes") {
      val (body, _) = drive(204)

      body.shouldBeInstanceOf<BoundedBytes.Ok>().bytes.size shouldBe 0
    }

    it("stops at cap + 1 across buffers without Content-Length: TooLarge and the subscription is cancelled") {
      val (body, subscription) = drive(200, publishes = listOf(buffers("12345"), buffers("6789"), buffers("more")))

      body shouldBeSameInstanceAs BoundedBytes.TooLarge
      subscription.cancelled shouldBe true
    }

    it("answers TooLarge from a Content-Length over the cap without requesting the body") {
      val (body, subscription) = drive(200, headers = mapOf("Content-Length" to listOf("${CAP + 1}")))

      body shouldBeSameInstanceAs BoundedBytes.TooLarge
      subscription.cancelled shouldBe true
      subscription.requested shouldBe 0L
    }

    it("reads normally when Content-Length is within the cap") {
      val (body, _) = drive(200, headers = mapOf("Content-Length" to listOf("$CAP")), publishes = listOf(buffers("ok")))

      body.shouldBeInstanceOf<BoundedBytes.Ok>().bytes.decodeToString() shouldBe "ok"
    }
  }

  describe("BoundedBytesHandler on a non-2xx response") {
    it("discards the body and yields null") {
      val (body, _) = drive(404, publishes = listOf(buffers("secret error body")))

      body.shouldBeNull()
    }
  }
})
