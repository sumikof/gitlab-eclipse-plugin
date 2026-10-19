package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.http.GitLabHttpClient
import com.gitlab.eclipse.authentication.GitLabTokenProviderManager
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class GitLabApiClientFetchBytesTest : DescribeSpec({
  val http = mockk<GitLabHttpClient>()
  val tokens = mockk<GitLabTokenProviderManager>()
  val prefs = mockk<ScopedPreferenceStore>()
  val client = GitLabApiClient(http, tokens, prefs)

  val connection = ConnectionSnapshot(
    instanceUrl = "https://pinned.example.com/",
    token = "pinned-token",
    authFingerprint = "0123456789abcdef",
    configGeneration = 1L,
  )

  fun response(body: BoundedBytes?, status: Int, headers: Map<String, List<String>> = emptyMap()) =
    mockk<HttpResponse<BoundedBytes?>>().also {
      every { it.statusCode() } returns status
      every { it.body() } returns body
      every { it.headers() } returns HttpHeaders.of(headers) { _, _ -> true }
    }

  beforeEach { clearMocks(http, tokens, prefs) }

  describe("fetchBytes") {
    it("GETs the pinned instance with the pinned token, Accept */* and a 30 s timeout") {
      val request = slot<HttpRequest>()
      every { http.send(capture(request), any<HttpResponse.BodyHandler<BoundedBytes?>>()) } returns
        response(BoundedBytes.Ok(byteArrayOf(1, 2)), 200)

      client.fetchBytes("/projects/1/repository/files/a%2Fb/raw", mapOf("ref" to "abc"), 16, connection)

      request.captured.method() shouldBe "GET"
      request.captured.uri().toString() shouldBe
        "https://pinned.example.com/api/v4/projects/1/repository/files/a%2Fb/raw?ref=abc"
      request.captured.headers().firstValue("Authorization").get() shouldBe "Bearer pinned-token"
      request.captured.headers().firstValue("Accept").get() shouldBe "*/*"
      request.captured.timeout().get() shouldBe Duration.ofSeconds(30)
      verify(exactly = 0) { prefs.getString(any()) }
      verify(exactly = 0) { tokens.getToken(any()) }
    }

    it("reads the body under the cap with a 30 s body deadline (R1)") {
      val handler = slot<HttpResponse.BodyHandler<BoundedBytes?>>()
      every { http.send(any<HttpRequest>(), capture(handler)) } returns response(BoundedBytes.Ok(byteArrayOf()), 200)

      client.fetchBytes("/p", emptyMap(), 16, connection)

      handler.captured.shouldBeInstanceOf<BoundedBytesHandler>().bodyTimeout shouldBe Duration.ofSeconds(30)
    }

    it("returns the handler's bytes on 2xx") {
      every { http.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<BoundedBytes?>>()) } returns
        response(BoundedBytes.Ok("hello".toByteArray()), 200)

      val result = client.fetchBytes("/p", emptyMap(), 16, connection)

      result.shouldBeInstanceOf<BoundedBytes.Ok>().bytes.decodeToString() shouldBe "hello"
    }

    it("passes TooLarge through") {
      every { http.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<BoundedBytes?>>()) } returns
        response(BoundedBytes.TooLarge, 200)

      client.fetchBytes("/p", emptyMap(), 16, connection) shouldBeSameInstanceAs BoundedBytes.TooLarge
    }

    it("throws GitLabApiException with the status, an empty body and the correlation id on non-2xx") {
      every { http.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<BoundedBytes?>>()) } returns
        response(null, 403, mapOf("x-request-id" to listOf("corr-1")))

      val e = shouldThrow<GitLabApiException> { client.fetchBytes("/p", emptyMap(), 16, connection) }

      e.statusCode shouldBe 403
      e.responseBody shouldBe ""
      e.correlationId shouldBe "corr-1"
    }
  }
})
