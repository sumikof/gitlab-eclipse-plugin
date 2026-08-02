package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.http.GitLabHttpClient
import com.gitlab.eclipse.authentication.GitLabTokenProviderManager
import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
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
import java.nio.charset.StandardCharsets

class GitLabApiClientPostJsonTest : DescribeSpec({
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

  describe("postJson") {
    it("sends POST to the connection's instance with the connection's Bearer token, never the global prefs/token") {
      val captured = slot<HttpRequest>()
      every { http.send(capture(captured)) } returns response("merged: yaml", status = 200)

      client.postJson("/projects/1/ci/lint", "{\"content\":\"x\"}", connection = connection)

      captured.captured.method() shouldBe "POST"
      captured.captured.uri().toString() shouldBe "https://pinned.example.com/api/v4/projects/1/ci/lint?"
      captured.captured.headers().firstValue("Authorization").get() shouldBe "Bearer pinned-token"
      verify(exactly = 0) { prefs.getString(any()) }
      verify(exactly = 0) { tokens.getToken() }
    }

    it("sends Content-Type and Accept: application/json headers") {
      val captured = slot<HttpRequest>()
      every { http.send(capture(captured)) } returns response("{}", status = 200)

      client.postJson("/projects/1/ci/lint", "{\"content\":\"x\"}", connection = connection)

      captured.captured.headers().firstValue("Content-Type").get() shouldBe "application/json"
      captured.captured.headers().firstValue("Accept").get() shouldBe "application/json"
    }

    it("sends the jsonBody as the request body") {
      val captured = slot<HttpRequest>()
      every { http.send(capture(captured)) } returns response("{}", status = 200)
      val jsonBody = "{\"content\":\"stages:\\n  - test\"}"

      client.postJson("/projects/1/ci/lint", jsonBody, connection = connection)

      captured.captured.bodyPublisher().get().contentLength() shouldBe
        jsonBody.toByteArray(StandardCharsets.UTF_8).size.toLong()
    }

    it("returns the response body text on 200") {
      every { http.send(any()) } returns response("merged: yaml", status = 200)

      val result = client.postJson("/projects/1/ci/lint", "{\"content\":\"x\"}", connection = connection)

      result shouldBe "merged: yaml"
    }

    it("returns the response body text on 201") {
      every { http.send(any()) } returns response("created", status = 201)

      val result = client.postJson("/projects/1/ci/lint", "{\"content\":\"x\"}", connection = connection)

      result shouldBe "created"
    }

    it("throws GitLabApiException carrying status, body, and correlationId on a non-2xx") {
      every { http.send(any()) } returns
        response("bad", status = 400, headers = mapOf("x-request-id" to listOf("corr-x")))

      val exception = shouldThrow<GitLabApiException> {
        client.postJson("/projects/1/ci/lint", "{\"content\":\"x\"}", connection = connection)
      }

      exception.statusCode shouldBe 400
      exception.responseBody shouldBe "bad"
      exception.correlationId shouldBe "corr-x"
    }

    it("propagates HttpTimeoutException without swallowing it") {
      every { http.send(any()) } throws HttpTimeoutException("request timed out")

      shouldThrow<HttpTimeoutException> {
        client.postJson("/projects/1/ci/lint", "{\"content\":\"x\"}", connection = connection)
      }
    }

    it("propagates IOException without swallowing it") {
      every { http.send(any()) } throws IOException("connection reset")

      shouldThrow<IOException> {
        client.postJson("/projects/1/ci/lint", "{\"content\":\"x\"}", connection = connection)
      }
    }
  }
})
