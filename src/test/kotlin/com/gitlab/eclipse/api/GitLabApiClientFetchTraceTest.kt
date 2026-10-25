package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.http.GitLabHttpClient
import com.gitlab.eclipse.authentication.GitLabTokenProviderManager
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

private class RecordingDeadline : BodyDeadline {
  var timeout: Duration? = null
  var onExpiry: (() -> Unit)? = null
  var disarmed = 0

  override fun arm(timeout: Duration, onExpiry: () -> Unit): () -> Unit {
    this.timeout = timeout
    this.onExpiry = onExpiry
    return { disarmed++ }
  }
}

class GitLabApiClientFetchTraceTest : DescribeSpec({
  val http = mockk<GitLabHttpClient>()
  val tokens = mockk<GitLabTokenProviderManager>()
  val prefs = mockk<ScopedPreferenceStore>()
  val deadline = RecordingDeadline()
  val client = GitLabApiClient(http, tokens, prefs, bodyDeadline = deadline)

  val connection = ConnectionSnapshot(
    instanceUrl = "https://pinned.example.com/",
    token = "pinned-token",
    authFingerprint = "0123456789abcdef",
    configGeneration = 1L,
  )

  fun traceResponse(outcome: TailOutcome) =
    mockk<HttpResponse<TailOutcome>>().also { every { it.body() } returns outcome }

  fun streamResponse(stream: InputStream, status: Int, headers: Map<String, List<String>> = emptyMap()) =
    mockk<HttpResponse<InputStream>>().also {
      every { it.statusCode() } returns status
      every { it.body() } returns stream
      every { it.headers() } returns HttpHeaders.of(headers) { _, _ -> true }
    }

  beforeEach {
    clearMocks(http, tokens, prefs)
    deadline.timeout = null
    deadline.onExpiry = null
    deadline.disarmed = 0
  }

  describe("fetchTrace") {
    it("GETs the pinned instance with the pinned token, Accept */*, a 30 s timeout and no If-None-Match") {
      val request = slot<HttpRequest>()
      every { http.send(capture(request), any<HttpResponse.BodyHandler<TailOutcome>>()) } returns
        traceResponse(TailOutcome.Done(TraceFetch.NotModified))

      client.fetchTrace("/projects/1/jobs/2/trace", null, 16, connection)

      request.captured.method() shouldBe "GET"
      request.captured.uri().toString() shouldStartWith "https://pinned.example.com/api/v4/projects/1/jobs/2/trace"
      request.captured.headers().firstValue("Authorization").get() shouldBe "Bearer pinned-token"
      request.captured.headers().firstValue("Accept").get() shouldBe "*/*"
      request.captured.headers().firstValue("If-None-Match").isPresent shouldBe false
      request.captured.timeout().get() shouldBe Duration.ofSeconds(30)
      verify(exactly = 0) { prefs.getString(any()) }
      verify(exactly = 0) { tokens.getToken(any()) }
    }

    it("sends the given etag unchanged as If-None-Match, weak validators included") {
      val request = slot<HttpRequest>()
      every { http.send(capture(request), any<HttpResponse.BodyHandler<TailOutcome>>()) } returns
        traceResponse(TailOutcome.Done(TraceFetch.NotModified))

      client.fetchTrace("/p", "W/\"abc\"", 16, connection)

      request.captured.headers().firstValue("If-None-Match").get() shouldBe "W/\"abc\""
    }

    it("reads the body with a tail-keeping handler using the cap and a 30 s body timeout") {
      val handler = slot<HttpResponse.BodyHandler<TailOutcome>>()
      every { http.send(any<HttpRequest>(), capture(handler)) } returns
        traceResponse(TailOutcome.Done(TraceFetch.NotModified))

      client.fetchTrace("/p", null, 16, connection)

      handler.captured.shouldBeInstanceOf<TailKeepingBodyHandler>().bodyTimeout shouldBe Duration.ofSeconds(30)
    }

    it("returns NotModified and bodies as the handler produced them") {
      every { http.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<TailOutcome>>()) } returns
        traceResponse(TailOutcome.Done(TraceFetch.NotModified))
      client.fetchTrace("/p", null, 16, connection) shouldBeSameInstanceAs TraceFetch.NotModified

      val fetched = TraceFetch.Body("x".toByteArray(), "\"e\"", 5L, 4L)
      every { http.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<TailOutcome>>()) } returns
        traceResponse(TailOutcome.Done(fetched))
      client.fetchTrace("/p", null, 16, connection) shouldBeSameInstanceAs fetched
    }

    it("throws GitLabApiException with status, empty body and correlation id for a rejected status") {
      every { http.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<TailOutcome>>()) } returns
        traceResponse(TailOutcome.Rejected(403, "corr-1"))

      val e = shouldThrow<GitLabApiException> { client.fetchTrace("/p", null, 16, connection) }

      e.statusCode shouldBe 403
      e.responseBody shouldBe ""
      e.correlationId shouldBe "corr-1"
    }
  }

  describe("downloadTo") {
    it("GETs the pinned instance, hands the stream to the sink and arms a 10 minute deadline") {
      val request = slot<HttpRequest>()
      every { http.send(capture(request), any<HttpResponse.BodyHandler<InputStream>>()) } returns
        streamResponse(ByteArrayInputStream("hello".toByteArray()), 200)
      var read = ""

      client.downloadTo("/projects/1/jobs/2/trace", connection) { read = it.readBytes().decodeToString() }

      read shouldBe "hello"
      request.captured.headers().firstValue("Authorization").get() shouldBe "Bearer pinned-token"
      request.captured.headers().firstValue("Accept").get() shouldBe "*/*"
      deadline.timeout shouldBe Duration.ofMinutes(10)
      deadline.disarmed shouldBe 1
    }

    it("throws GitLabApiException for a non-2xx status without calling the sink") {
      every { http.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<InputStream>>()) } returns
        streamResponse(ByteArrayInputStream("error page".toByteArray()), 404, mapOf("x-request-id" to listOf("c-1")))
      var called = false

      val e = shouldThrow<GitLabApiException> { client.downloadTo("/p", connection) { called = true } }

      e.statusCode shouldBe 404
      e.responseBody shouldBe ""
      e.correlationId shouldBe "c-1"
      called shouldBe false
      deadline.disarmed shouldBe 1
    }

    it("turns a read failure after the deadline fired into HttpTimeoutException and closes the stream") {
      val closed = AtomicBoolean(false)
      val stream = object : InputStream() {
        override fun read(): Int {
          if (closed.get()) throw IOException("Stream closed")
          return 'a'.code
        }

        override fun close() {
          closed.set(true)
        }
      }
      every { http.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<InputStream>>()) } returns
        streamResponse(stream, 200)

      shouldThrow<HttpTimeoutException> {
        client.downloadTo("/p", connection) {
          deadline.onExpiry!!.invoke()
          it.read()
        }
      }

      closed.get() shouldBe true
    }

    it("rethrows other I/O failures unchanged and closes the stream") {
      val closed = AtomicBoolean(false)
      val stream = object : InputStream() {
        override fun read(): Int = throw IOException("connection reset")

        override fun close() {
          closed.set(true)
        }
      }
      every { http.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<InputStream>>()) } returns
        streamResponse(stream, 200)

      val e = shouldThrow<IOException> { client.downloadTo("/p", connection) { it.read() } }

      e.message shouldBe "connection reset"
      closed.get() shouldBe true
    }
  }
})
