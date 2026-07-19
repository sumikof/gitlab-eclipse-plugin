package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.http.GitLabHttpClient
import com.gitlab.eclipse.authentication.GitLabTokenProviderManager
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.Platform
import org.eclipse.ui.preferences.ScopedPreferenceStore
import org.osgi.framework.Bundle
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
    clearMocks(http)
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

    it("stops without looping when x-next-page is non-numeric, and warns") {
      val log = mockk<ILog>(relaxUnitFun = true)
      every { Platform.getLog(any<Bundle>()) } returns log
      val localClient = GitLabApiClient(http, tokens, prefs)
      every { http.send(any()) } returns response("""[{"id":1}]""", nextPage = "next")

      val result = localClient.fetchListFromApi(req())

      result shouldBe listOf(Item(1))
      verify(exactly = 1) { http.send(any()) }
      verify(exactly = 1) { log.warn(match { it.contains("truncated") }) }
    }

    it("stops at MAX_PAGES (20) when x-next-page keeps advancing, and warns") {
      val log = mockk<ILog>(relaxUnitFun = true)
      every { Platform.getLog(any<Bundle>()) } returns log
      val localClient = GitLabApiClient(http, tokens, prefs)
      var call = 0
      every { http.send(any()) } answers {
        call++
        response("""[{"id":$call}]""", nextPage = (call + 1).toString())
      }

      val result = localClient.fetchListFromApi(req())

      result shouldBe (1..20).map { Item(it.toLong()) }
      verify(exactly = 20) { http.send(any()) }
      verify(exactly = 1) { log.warn(match { it.contains("truncated") }) }
    }
  }
})
