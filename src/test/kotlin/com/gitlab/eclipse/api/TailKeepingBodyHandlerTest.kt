package com.gitlab.eclipse.api

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
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
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

private const val CAP = 10

private class TailFakeSubscription : Flow.Subscription {
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

private class TailArm(val action: () -> Unit, var disarmed: Boolean = false)

private class TailManualDeadline : BodyDeadline {
  val armed = CompletableFuture<Pair<Duration, () -> Unit>>()
  val arms = mutableListOf<TailArm>()
  var disarmed = 0

  val active: Int get() = arms.count { !it.disarmed }

  override fun arm(timeout: Duration, onExpiry: () -> Unit): () -> Unit {
    armed.complete(timeout to onExpiry)
    val arm = TailArm(onExpiry)
    arms.add(arm)
    return {
      arm.disarmed = true
      disarmed++
    }
  }

  /** Fires the newest arm, like the timer would once the idle period has passed with no further re-arm. */
  fun fire() = arms.last().action()
}

private class TailClock(var nanos: Long = 0L) : () -> Long {
  override fun invoke(): Long = nanos
}

private fun buffers(chunks: List<ByteArray>): List<ByteBuffer> = chunks.map { ByteBuffer.wrap(it) }

/** Drives a subscriber through subscribe, each chunk as its own publish, and complete. */
private fun drive(
  status: Int,
  headers: Map<String, List<String>> = emptyMap(),
  chunks: List<ByteArray> = emptyList(),
  cap: Int = CAP,
): Pair<TailOutcome, TailFakeSubscription> {
  val subscriber = TailKeepingBodyHandler(cap).apply(info(status, headers))
  val subscription = TailFakeSubscription()
  subscriber.onSubscribe(subscription)
  chunks.forEach { subscriber.onNext(buffers(listOf(it))) }
  subscriber.onComplete()
  return subscriber.body.toCompletableFuture().get(1, TimeUnit.SECONDS) to subscription
}

private fun body(outcome: TailOutcome): TraceFetch.Body =
  outcome.shouldBeInstanceOf<TailOutcome.Done>().fetch.shouldBeInstanceOf<TraceFetch.Body>()

private fun split(bytes: ByteArray, size: Int): List<ByteArray> =
  bytes.toList().chunked(size).map { it.toByteArray() }

class TailKeepingBodyHandlerTest : DescribeSpec({
  describe("2xx bodies within the cap") {
    it("returns everything unchanged with no omission") {
      val (outcome, subscription) = drive(200, chunks = listOf("abc\n".toByteArray(), "de".toByteArray()))

      val b = body(outcome)
      b.bytes.decodeToString() shouldBe "abc\nde"
      b.totalBytes shouldBe 6L
      b.omittedBytes shouldBe 0L
      subscription.cancelled shouldBe false
      subscription.requested shouldBe Long.MAX_VALUE
    }

    it("keeps a body of exactly the cap, even without a line break at the start") {
      val (outcome, _) = drive(200, chunks = listOf("1234567890".toByteArray()))

      val b = body(outcome)
      b.bytes.decodeToString() shouldBe "1234567890"
      b.omittedBytes shouldBe 0L
    }

    it("returns an empty body") {
      body(drive(204).first).bytes.size shouldBe 0
    }

    it("passes the ETag through, weak validators included") {
      body(drive(200, mapOf("ETag" to listOf("W/\"abc\""))).first).etag shouldBe "W/\"abc\""
      body(drive(200).first).etag shouldBe null
    }
  }

  describe("2xx bodies over the cap") {
    // 3 lines of 6 bytes: line1\nline2\nline3\n (18 bytes). The last 10 bytes are "ne2\nline3\n".
    val text = "line1\nline2\nline3\n".toByteArray()

    it("keeps the tail and starts just after the first line break") {
      val b = body(drive(200, chunks = listOf(text)).first)

      b.bytes.decodeToString() shouldBe "line3\n"
      b.totalBytes shouldBe 18L
      b.omittedBytes shouldBe 12L
    }

    it("gives the same result however the body is chunked") {
      for (size in 1..text.size) {
        val b = body(drive(200, chunks = split(text, size)).first)
        b.bytes.decodeToString() shouldBe "line3\n"
        b.omittedBytes shouldBe 12L
      }
    }

    it("does not return an empty body when a giant last line is followed by a single line break") {
      val b = body(drive(200, chunks = listOf("abcdefghijklmnopqrstuvwxyz\n".toByteArray()), cap = 10).first)

      b.bytes.decodeToString() shouldBe "rstuvwxyz\n"
      b.totalBytes shouldBe 27L
      b.omittedBytes shouldBe 17L
    }

    it("aligns to a UTF-8 boundary when the only line break is beyond the first MiB of the tail") {
      val raw = "日".repeat(1_500_000).toByteArray() + "\nabc".toByteArray()
      val cap = 2_000_000

      val b = body(drive(200, chunks = split(raw, 65_536), cap = cap).first)

      // The kept tail starts on the last byte of a character, which is skipped; the break is not used.
      b.bytes.size shouldBe cap - 1
      b.bytes.decodeToString().startsWith("日") shouldBe true
      b.omittedBytes shouldBe raw.size - (cap - 1).toLong()
    }

    it("still starts after the first line break when it lies within the first MiB of the tail") {
      val raw = "y".repeat(100).toByteArray() + "\n".toByteArray() + "z".repeat(2 * 1024 * 1024).toByteArray()
      val cap = 2 * 1024 * 1024 + 51

      val b = body(drive(200, chunks = split(raw, 65_536), cap = cap).first)

      b.bytes.size shouldBe 2 * 1024 * 1024
      b.bytes[0] shouldBe 'z'.code.toByte()
      b.omittedBytes shouldBe 101L
    }

    it("without a line break starts at a UTF-8 character boundary") {
      // Four 3-byte characters (12 bytes); the last 8 bytes start inside the second character.
      val raw = "日日日日".toByteArray()

      val b = body(drive(200, chunks = split(raw, 5), cap = 8).first)

      b.bytes.decodeToString() shouldBe "日日"
      b.totalBytes shouldBe 12L
      b.omittedBytes shouldBe 6L
    }

    // Spec: only the first TAIL_LF_SEARCH_BYTES (1 MiB) of the kept tail are searched, i.e. indexes 0 until 1 MiB
    // (`for (i in 0 until searchEnd)`). A line break at index 1 MiB - 1 is used; one at index 1 MiB is not.
    // The tail is [2 continuation bytes][a...][LF][b...] so the two alignments give distinguishable results.
    fun boundaryTail(lfIndex: Int, tailSize: Int): ByteArray {
      val tail = ByteArray(tailSize) { 'b'.code.toByte() }
      for (i in 0 until lfIndex) tail[i] = 'a'.code.toByte()
      tail[0] = 0x80.toByte()
      tail[1] = 0x80.toByte()
      tail[lfIndex] = '\n'.code.toByte()
      return tail
    }

    it("uses a line break at the last searched index (1 MiB - 1 into the tail)") {
      val oneMiB = 1024 * 1024
      val cap = oneMiB + 10
      val raw = "xxxxx".toByteArray() + boundaryTail(oneMiB - 1, cap)

      val b = body(drive(200, chunks = split(raw, 65_536), cap = cap).first)

      b.bytes.size shouldBe 10 // the 10 'b' bytes after the break
      b.bytes[0] shouldBe 'b'.code.toByte()
      b.omittedBytes shouldBe raw.size - 10L
    }

    it("ignores a line break exactly 1 MiB into the tail and aligns to the UTF-8 boundary instead") {
      val oneMiB = 1024 * 1024
      val cap = oneMiB + 10
      val raw = "xxxxx".toByteArray() + boundaryTail(oneMiB, cap)

      val b = body(drive(200, chunks = split(raw, 65_536), cap = cap).first)

      b.bytes.size shouldBe cap - 2 // only the 2 leading continuation bytes are skipped
      b.bytes[0] shouldBe 'a'.code.toByte()
      b.omittedBytes shouldBe raw.size - (cap - 2).toLong()
    }

    it("handles a long single line with a bounded cap") {
      val raw = ByteArray(200_000) { 'x'.code.toByte() }

      val b = body(drive(200, chunks = split(raw, 4096), cap = 1000).first)

      b.bytes.size shouldBe 1000
      b.omittedBytes shouldBe 199_000L
    }
  }

  describe("304 and error statuses") {
    it("answers NotModified without requesting the body") {
      val (outcome, subscription) = drive(304)

      outcome.shouldBeInstanceOf<TailOutcome.Done>().fetch shouldBeSameInstanceAs TraceFetch.NotModified
      subscription.requested shouldBe 0L
      subscription.cancelled shouldBe true
    }

    it("rejects an error status with its correlation id and never reads the body") {
      val (outcome, subscription) = drive(
        500,
        mapOf("x-request-id" to listOf("corr-9")),
        chunks = listOf("secret error body".toByteArray()),
      )

      val rejected = outcome.shouldBeInstanceOf<TailOutcome.Rejected>()
      rejected.status shouldBe 500
      rejected.correlationId shouldBe "corr-9"
      subscription.requested shouldBe 0L
      subscription.cancelled shouldBe true
    }
  }

  describe("body deadline") {
    it("arms a 30 s deadline by default") {
      val deadline = TailManualDeadline()
      TailKeepingBodyHandler(CAP, deadline = deadline).apply(info(200)).onSubscribe(TailFakeSubscription())

      deadline.armed.get(1, TimeUnit.SECONDS).first shouldBe Duration.ofSeconds(30)
    }

    it("on expiry cancels and fails the body with HttpTimeoutException") {
      val deadline = TailManualDeadline()
      val subscriber = TailKeepingBodyHandler(CAP, Duration.ofSeconds(3), deadline).apply(info(200))
      val subscription = TailFakeSubscription()
      subscriber.onSubscribe(subscription)
      subscriber.onNext(buffers(listOf("abc".toByteArray())))

      deadline.fire()

      subscription.cancelled shouldBe true
      val e = shouldThrow<ExecutionException> { subscriber.body.toCompletableFuture().get(1, TimeUnit.SECONDS) }
      (e.cause is HttpTimeoutException) shouldBe true
    }

    it("keeps pushing the deadline back while data keeps flowing, at most once a second") {
      val deadline = TailManualDeadline()
      val clock = TailClock()
      val subscriber = TailKeepingBodyHandler(CAP, Duration.ofSeconds(30), deadline, clock).apply(info(200))
      val subscription = TailFakeSubscription()
      subscriber.onSubscribe(subscription)

      repeat(5) {
        clock.nanos += Duration.ofMillis(400).toNanos()
        subscriber.onNext(buffers(listOf("a".toByteArray())))
      }
      deadline.arms.size shouldBe 2 // armed at 0 s, re-armed at 1.2 s; the 0.4 s steps in between are skipped

      repeat(100) {
        clock.nanos += Duration.ofSeconds(20).toNanos() // far more than 30 s of wall time in total
        subscriber.onNext(buffers(listOf("a".toByteArray())))
      }
      deadline.active shouldBe 1
      subscription.cancelled shouldBe false
      subscriber.body.toCompletableFuture().isDone shouldBe false
    }

    it("times out when the body goes silent") {
      val deadline = TailManualDeadline()
      val clock = TailClock()
      val subscriber = TailKeepingBodyHandler(CAP, Duration.ofSeconds(30), deadline, clock).apply(info(200))
      val subscription = TailFakeSubscription()
      subscriber.onSubscribe(subscription)
      clock.nanos += Duration.ofSeconds(2).toNanos()
      subscriber.onNext(buffers(listOf("abc".toByteArray())))

      deadline.fire() // no chunk since the last re-arm

      subscription.cancelled shouldBe true
      val e = shouldThrow<ExecutionException> { subscriber.body.toCompletableFuture().get(1, TimeUnit.SECONDS) }
      (e.cause is HttpTimeoutException) shouldBe true
    }

    it("disarms the deadline when the body completes or fails") {
      val completed = TailManualDeadline()
      TailKeepingBodyHandler(CAP, deadline = completed).apply(info(200)).also {
        it.onSubscribe(TailFakeSubscription())
        it.onComplete()
      }
      completed.disarmed shouldBe 1

      val failed = TailManualDeadline()
      TailKeepingBodyHandler(CAP, deadline = failed).apply(info(200)).also {
        it.onSubscribe(TailFakeSubscription())
        it.onError(java.io.IOException("reset"))
      }
      failed.disarmed shouldBe 1
    }
  }

  describe("with a real HttpClient") {
    it("keeps the tail of a chunked response and reads the ETag") {
      val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
      server.createContext("/trace") { exchange ->
        exchange.responseHeaders.add("ETag", "\"v1\"")
        exchange.sendResponseHeaders(200, 0) // chunked
        listOf("first\n", "second\n", "third\n").forEach {
          exchange.responseBody.write(it.toByteArray())
          exchange.responseBody.flush()
        }
        exchange.close()
      }
      server.start()
      val client = HttpClient.newHttpClient()
      try {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.address.port}/trace")).build()

        val response = client.send(request, TailKeepingBodyHandler(10))

        val b = body(response.body())
        b.bytes.decodeToString() shouldBe "third\n"
        b.totalBytes shouldBe 19L
        b.etag shouldBe "\"v1\""
      } finally {
        client.shutdownNow()
        server.stop(0)
      }
    }
  }
})
