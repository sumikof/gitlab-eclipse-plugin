package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.http.GitLabHttpClient
import com.gitlab.eclipse.authentication.GitLabTokenProviderManager
import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.io.IOException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException

class GitLabApiClientFetchTextTest : DescribeSpec({
  val http = mockk<GitLabHttpClient>()
  val tokens = mockk<GitLabTokenProviderManager>()
  val prefs = mockk<ScopedPreferenceStore>()
  val client = GitLabApiClient(http, tokens, prefs)

  val connection = ConnectionSnapshot(
    instanceUrl = "https://pinned.example.com/",
    token = "pinned-token",
    authFingerprint = "0123456789abcdef",
    configGeneration = 42L,
  )

  fun response(body: String, status: Int = 200, headers: Map<String, List<String>> = emptyMap()): HttpResponse<String> {
    val resp = mockk<HttpResponse<String>>()
    every { resp.statusCode() } returns status
    every { resp.body() } returns body
    every { resp.headers() } returns java.net.http.HttpHeaders.of(headers) { _, _ -> true }
    return resp
  }

  beforeEach {
    clearMocks(http, tokens, prefs)
    // Global settings deliberately DIFFERENT from the pinned connection: fetchText must
    // never read them, otherwise a mid-session settings change misroutes the read / leaks
    // the wrong credential.
    every { tokens.getToken() } returns "global-token"
    every { prefs.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns "https://global.example.com"
  }

  describe("fetchText") {
    it("returns the raw body text on 2xx") {
      every { http.send(any()) } returns response("raw trace text\nline2", status = 200)

      val result = client.fetchText("/projects/1/jobs/9/trace", connection = connection)

      result shouldBe "raw trace text\nline2"
    }

    it("sends GET to the connection's instance with the connection's Bearer token, never the global prefs/token") {
      val captured = slot<HttpRequest>()
      every { http.send(capture(captured)) } returns response("trace", status = 200)

      client.fetchText("/projects/1/jobs/9/trace", connection = connection)

      captured.captured.method() shouldBe "GET"
      captured.captured.uri().toString() shouldBe "https://pinned.example.com/api/v4/projects/1/jobs/9/trace?"
      captured.captured.headers().firstValue("Authorization").get() shouldBe "Bearer pinned-token"
      verify(exactly = 0) { prefs.getString(any()) }
      verify(exactly = 0) { tokens.getToken() }
    }

    it("falls through to the global-read path (captureConnection) when connection is omitted") {
      val captured = slot<HttpRequest>()
      every { http.send(capture(captured)) } returns response("trace", status = 200)

      client.fetchText("/projects/1/jobs/9/trace")

      captured.captured.method() shouldBe "GET"
      captured.captured.uri().toString() shouldBe "https://global.example.com/api/v4/projects/1/jobs/9/trace?"
      captured.captured.headers().firstValue("Authorization").get() shouldBe "Bearer global-token"
    }

    it("throws GitLabApiException carrying status, body, and correlationId on a non-2xx") {
      every { http.send(any()) } returns
        response("not found", status = 404, headers = mapOf("x-request-id" to listOf("corr-404")))

      val exception = shouldThrow<GitLabApiException> {
        client.fetchText("/projects/1/jobs/9/trace", connection = connection)
      }

      exception.statusCode shouldBe 404
      exception.responseBody shouldBe "not found"
      exception.correlationId shouldBe "corr-404"
    }

    it("has null correlationId on a non-2xx when x-request-id is absent") {
      every { http.send(any()) } returns response("not found", status = 404)

      val exception = shouldThrow<GitLabApiException> {
        client.fetchText("/projects/1/jobs/9/trace", connection = connection)
      }

      exception.correlationId.shouldBeNull()
    }

    it("propagates HttpTimeoutException without swallowing it") {
      every { http.send(any()) } throws HttpTimeoutException("request timed out")

      shouldThrow<HttpTimeoutException> {
        client.fetchText("/projects/1/jobs/9/trace", connection = connection)
      }
    }

    it("propagates IOException without swallowing it") {
      every { http.send(any()) } throws IOException("connection reset")

      shouldThrow<IOException> {
        client.fetchText("/projects/1/jobs/9/trace", connection = connection)
      }
    }
  }
})
