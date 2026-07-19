package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.http.GitLabHttpClient
import com.gitlab.eclipse.authentication.GitLabTokenProviderManager
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class GitLabApiClientTest : DescribeSpec({
  val http = mockk<GitLabHttpClient>()
  val tokens = mockk<GitLabTokenProviderManager>()
  val prefs = mockk<ScopedPreferenceStore>(relaxed = true)
  val client = GitLabApiClient(http, tokens, prefs)

  extensions(LoggingKotestExtension)

  data class Item(val id: Long)
  fun req() = ApiRequest("/issues", mapOf("scope" to "assigned_to_me"), Item::class.java)

  fun response(body: String, status: Int = 200, nextPage: String = ""): HttpResponse<String> {
    val resp = mockk<HttpResponse<String>>()
    every { resp.statusCode() } returns status
    every { resp.body() } returns body
    val headers = java.net.http.HttpHeaders.of(
      mapOf("x-next-page" to listOf(nextPage)),
    ) { _, _ -> true }
    every { resp.headers() } returns headers
    return resp
  }

  beforeEach {
    every { tokens.getToken() } returns "tok-123"
    every { prefs.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns "https://gitlab.example.com/"
  }

  describe("fetchListFromApi") {
    it("builds the URL, sends Bearer auth, and parses a single page") {
      val captured = slot<HttpRequest>()
      every { http.send(capture(captured)) } returns response("""[{"id":1},{"id":2}]""")

      val result = client.fetchListFromApi(req())

      result shouldBe listOf(Item(1), Item(2))
      captured.captured.uri().toString() shouldBe
        "https://gitlab.example.com/api/v4/issues?scope=assigned_to_me&per_page=100&page=1"
      captured.captured.headers().firstValue("Authorization").get() shouldBe "Bearer tok-123"
    }

    it("aggregates all pages until x-next-page is empty") {
      every { http.send(any()) } returnsMany listOf(
        response("""[{"id":1}]""", nextPage = "2"),
        response("""[{"id":2}]""", nextPage = ""),
      )
      client.fetchListFromApi(req()) shouldBe listOf(Item(1), Item(2))
    }

    it("throws GitLabApiException on a non-2xx and does not return partial results") {
      every { http.send(any()) } returnsMany listOf(
        response("""[{"id":1}]""", nextPage = "2"),
        response("boom", status = 500),
      )
      shouldThrow<GitLabApiException> { client.fetchListFromApi(req()) }
    }
  }
})
