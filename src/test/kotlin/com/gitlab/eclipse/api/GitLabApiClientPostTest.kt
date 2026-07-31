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

class GitLabApiClientPostTest : DescribeSpec({
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
    // Global settings deliberately DIFFERENT from the pinned connection: the POST path must
    // never read them, otherwise a mid-session settings change misroutes the write / leaks
    // the wrong credential.
    every { tokens.getToken() } returns "global-token"
    every { prefs.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns "https://global.example.com"
  }

  describe("post") {
    it("sends POST to the connection's instance with the connection's Bearer token, never the global prefs/token") {
      val captured = slot<HttpRequest>()
      every { http.send(capture(captured)) } returns response("", status = 201)

      client.post("/projects/1/pipelines/5/retry", connection = connection)

      captured.captured.method() shouldBe "POST"
      captured.captured.uri().toString() shouldBe "https://pinned.example.com/api/v4/projects/1/pipelines/5/retry?"
      captured.captured.headers().firstValue("Authorization").get() shouldBe "Bearer pinned-token"
      verify(exactly = 0) { prefs.getString(any()) }
      verify(exactly = 0) { tokens.getToken() }
    }

    it("appends encoded query parameters to the pinned base URL") {
      val captured = slot<HttpRequest>()
      every { http.send(capture(captured)) } returns response("", status = 200)

      client.post("/projects/1/jobs/9/play", query = mapOf("k" to "v v"), connection = connection)

      captured.captured.uri().toString() shouldBe "https://pinned.example.com/api/v4/projects/1/jobs/9/play?k=v+v"
    }

    it("sends an empty body and Accept: application/json") {
      val captured = slot<HttpRequest>()
      every { http.send(capture(captured)) } returns response("", status = 200)

      client.post("/projects/1/pipelines/5/cancel", connection = connection)

      captured.captured.bodyPublisher().get().contentLength() shouldBe 0L
      captured.captured.headers().firstValue("Accept").get() shouldBe "application/json"
    }

    it("returns PostResult with the status and the x-request-id correlation id on 2xx") {
      every { http.send(any()) } returns
        response("", status = 201, headers = mapOf("x-request-id" to listOf("corr-1")))

      val result = client.post("/projects/1/pipelines/5/retry", connection = connection)

      result shouldBe PostResult(httpStatus = 201, correlationId = "corr-1")
    }

    it("returns PostResult with null correlationId when x-request-id is absent") {
      every { http.send(any()) } returns response("", status = 200)

      val result = client.post("/projects/1/pipelines/5/retry", connection = connection)

      result.httpStatus shouldBe 200
      result.correlationId.shouldBeNull()
    }

    it("throws GitLabApiException carrying status, body, and correlationId on a non-2xx") {
      every { http.send(any()) } returns
        response("forbidden", status = 403, headers = mapOf("x-request-id" to listOf("corr-403")))

      val exception = shouldThrow<GitLabApiException> {
        client.post("/projects/1/pipelines/5/retry", connection = connection)
      }

      exception.statusCode shouldBe 403
      exception.responseBody shouldBe "forbidden"
      exception.correlationId shouldBe "corr-403"
    }

    it("propagates HttpTimeoutException without swallowing it") {
      every { http.send(any()) } throws HttpTimeoutException("request timed out")

      shouldThrow<HttpTimeoutException> {
        client.post("/projects/1/pipelines/5/retry", connection = connection)
      }
    }

    it("propagates IOException without swallowing it") {
      every { http.send(any()) } throws IOException("connection reset")

      shouldThrow<IOException> {
        client.post("/projects/1/pipelines/5/retry", connection = connection)
      }
    }
  }
})
