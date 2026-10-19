package com.gitlab.eclipse.api

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
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

/** A deadline the test fires by hand: records the armed timeout and holds the expiry action. */
private class ManualDeadline : BodyDeadline {
  val armed = CompletableFuture<Pair<Duration, () -> Unit>>()
  var disarmed = 0

  override fun arm(timeout: Duration, onExpiry: () -> Unit): () -> Unit {
    armed.complete(timeout to onExpiry)
    return { disarmed++ }
  }

  fun fire() = armed.get(5, TimeUnit.SECONDS).second()
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

  describe("BoundedBytesHandler body deadline (R1)") {
    it("arms a 30 s deadline by default when the body starts") {
      val deadline = ManualDeadline()
      BoundedBytesHandler(CAP, deadline = deadline).apply(info(200)).onSubscribe(FakeSubscription())

      deadline.armed.get(1, TimeUnit.SECONDS).first shouldBe Duration.ofSeconds(30)
    }

    it("on expiry cancels the subscription and fails the body with HttpTimeoutException") {
      val deadline = ManualDeadline()
      val subscriber = BoundedBytesHandler(CAP, Duration.ofSeconds(3), deadline).apply(info(200))
      val subscription = FakeSubscription()
      subscriber.onSubscribe(subscription)
      subscriber.onNext(buffers("abc"))

      deadline.fire()

      subscription.cancelled shouldBe true
      val e = shouldThrow<ExecutionException> { subscriber.body.toCompletableFuture().get(1, TimeUnit.SECONDS) }
      (e.cause is HttpTimeoutException) shouldBe true
    }

    it("an expiry after the body completed changes nothing") {
      val deadline = ManualDeadline()
      val subscriber = BoundedBytesHandler(CAP, deadline = deadline).apply(info(200))
      val subscription = FakeSubscription()
      subscriber.onSubscribe(subscription)
      subscriber.onNext(buffers("ok"))
      subscriber.onComplete()

      deadline.fire()

      subscription.cancelled shouldBe false
      subscriber.body.toCompletableFuture().get(1, TimeUnit.SECONDS)
        .shouldBeInstanceOf<BoundedBytes.Ok>().bytes.decodeToString() shouldBe "ok"
    }

    it("makes a real HttpClient send() throw HttpTimeoutException when the server stalls mid-body") {
      val release = CountDownLatch(1)
      val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
      server.createContext("/raw") { exchange ->
        exchange.sendResponseHeaders(200, 0) // chunked, no Content-Length
        exchange.responseBody.write("abc".toByteArray())
        exchange.responseBody.flush()
        release.await(30, TimeUnit.SECONDS) // stalls until the test is done
        runCatching { exchange.close() }
      }
      server.start()
      val client = HttpClient.newHttpClient()
      try {
        val deadline = ManualDeadline()
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.address.port}/raw")).build()
        val sent = CompletableFuture.supplyAsync { client.send(request, BoundedBytesHandler(CAP, deadline = deadline)) }

        deadline.fire()

        val e = shouldThrow<ExecutionException> { sent.get(10, TimeUnit.SECONDS) }
        (e.cause is HttpTimeoutException) shouldBe true
      } finally {
        release.countDown()
        client.shutdownNow()
        server.stop(0)
      }
    }
  }

  describe("BoundedBytesHandler deadline disarm (R2-3)") {
    fun subscribed(deadline: ManualDeadline): HttpResponse.BodySubscriber<BoundedBytes?> =
      BoundedBytesHandler(CAP, deadline = deadline).apply(info(200)).also { it.onSubscribe(FakeSubscription()) }

    it("disarms the deadline when the body completes") {
      val deadline = ManualDeadline()
      val subscriber = subscribed(deadline)

      subscriber.onNext(buffers("ok"))
      subscriber.onComplete()

      deadline.disarmed shouldBe 1
    }

    it("disarms the deadline when the body fails") {
      val deadline = ManualDeadline()

      subscribed(deadline).onError(java.io.IOException("reset"))

      deadline.disarmed shouldBe 1
    }

    it("disarms the deadline when the body passes the cap") {
      val deadline = ManualDeadline()

      subscribed(deadline).onNext(buffers("123456789"))

      deadline.disarmed shouldBe 1
    }

    it("the production deadline forgets a disarmed timer at once") {
      val before = SharedBodyDeadline.pendingForTest()

      val disarm = SharedBodyDeadline.arm(Duration.ofHours(1)) {}
      SharedBodyDeadline.pendingForTest() shouldBe before + 1
      disarm()

      SharedBodyDeadline.pendingForTest() shouldBe before
    }
  }
})
