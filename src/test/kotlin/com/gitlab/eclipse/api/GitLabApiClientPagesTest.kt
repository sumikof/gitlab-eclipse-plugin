package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.http.GitLabHttpClient
import com.gitlab.eclipse.authentication.GitLabTokenProviderManager
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import kotlin.coroutines.cancellation.CancellationException

/**
 * Covers [GitLabApiClient.fetchListPages]: a result-limited single request, the all-pages loop
 * with a truncation flag, between-page cancellation, connection pinning and query encoding.
 */
class GitLabApiClientPagesTest : DescribeSpec({
  val http = mockk<GitLabHttpClient>()
  val tokens = mockk<GitLabTokenProviderManager>()
  val prefs = mockk<ScopedPreferenceStore>()
  val client = GitLabApiClient(http, tokens, prefs)

  extensions(LoggingKotestExtension)

  data class Item(val id: Long)
  fun req(query: Map<String, String> = mapOf("state" to "opened")) =
    ApiRequest("/merge_requests", query, Item::class.java)

  val connection = ConnectionSnapshot(
    instanceUrl = "https://pinned.example.com/",
    token = "pinned-token",
    authFingerprint = "0123456789abcdef",
    configGeneration = 4L,
  )

  fun response(body: String, nextPage: String = ""): HttpResponse<String> {
    val resp = mockk<HttpResponse<String>>()
    every { resp.statusCode() } returns 200
    every { resp.body() } returns body
    val headers = java.net.http.HttpHeaders.of(mapOf("x-next-page" to listOf(nextPage))) { _, _ -> true }
    every { resp.headers() } returns headers
    return resp
  }

  fun pageOf(request: HttpRequest): Int =
    Regex("[?&]page=(\\d+)").find(request.uri().rawQuery.let { "?$it" })!!.groupValues[1].toInt()

  val sent = mutableListOf<HttpRequest>()

  beforeEach {
    clearMocks(http, tokens, prefs)
    sent.clear()
    every { tokens.getToken(any()) } returns "global-token"
    every { prefs.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns "https://global.example.com"
  }

  describe("with a result limit") {
    it("sends exactly one request with per_page=maxItems&page=1 and is never truncated") {
      every { http.send(capture(sent)) } returns response("""[{"id":1},{"id":2}]""", nextPage = "2")

      val result = client.fetchListPages(req(), 20, { true }, connection)

      result shouldBe PagedResult(listOf(Item(1), Item(2)), truncated = false)
      sent shouldHaveSize 1
      sent[0].uri().toString() shouldBe
        "https://pinned.example.com/api/v4/merge_requests?state=opened&per_page=20&page=1"
    }

    it("rejects a limit outside 1..100") {
      shouldThrow<IllegalArgumentException> { client.fetchListPages(req(), 0, { true }, connection) }
      shouldThrow<IllegalArgumentException> { client.fetchListPages(req(), 101, { true }, connection) }
      verify(exactly = 0) { http.send(any()) }
    }
  }

  describe("without a result limit") {
    it("follows x-next-page with per_page=100 and concatenates pages in order") {
      every { http.send(capture(sent)) } answers {
        when (pageOf(sent.last())) {
          1 -> response("""[{"id":1},{"id":2}]""", nextPage = "2")
          2 -> response("""[{"id":3}]""", nextPage = "3")
          else -> response("""[{"id":4}]""", nextPage = "")
        }
      }

      val result = client.fetchListPages(req(), null, { true }, connection)

      result shouldBe PagedResult(listOf(Item(1), Item(2), Item(3), Item(4)), truncated = false)
      sent shouldHaveSize 3
      sent.map { it.uri().rawQuery } shouldBe listOf(
        "state=opened&per_page=100&page=1",
        "state=opened&per_page=100&page=2",
        "state=opened&per_page=100&page=3",
      )
    }

    it("stops at the 20-page cap and reports truncation") {
      every { http.send(capture(sent)) } answers {
        val page = pageOf(sent.last())
        response("""[{"id":$page}]""", nextPage = (page + 1).toString())
      }

      val result = client.fetchListPages(req(), null, { true }, connection)

      sent shouldHaveSize 20
      result.truncated shouldBe true
      result.items shouldBe (1L..20L).map { Item(it) }
    }

    it("reports truncation on a non-increasing x-next-page") {
      every { http.send(capture(sent)) } answers {
        if (pageOf(sent.last()) == 1) response("""[{"id":1}]""", "2") else response("""[{"id":2}]""", "1")
      }

      val result = client.fetchListPages(req(), null, { true }, connection)

      sent shouldHaveSize 2
      result shouldBe PagedResult(listOf(Item(1), Item(2)), truncated = true)
    }

    it("reports truncation on a non-numeric x-next-page") {
      every { http.send(capture(sent)) } returns response("""[{"id":1}]""", nextPage = "abc")

      val result = client.fetchListPages(req(), null, { true }, connection)

      sent shouldHaveSize 1
      result shouldBe PagedResult(listOf(Item(1)), truncated = true)
    }

    it("checks isActive before every page and throws CancellationException after page 1") {
      every { http.send(capture(sent)) } returns response("""[{"id":1}]""", nextPage = "2")
      var calls = 0

      shouldThrow<CancellationException> {
        client.fetchListPages(req(), null, { calls++ == 0 }, connection)
      }

      sent shouldHaveSize 1
    }

    it("does not send any request when already inactive") {
      shouldThrow<CancellationException> { client.fetchListPages(req(), null, { false }, connection) }
      verify(exactly = 0) { http.send(any()) }
    }
  }

  describe("connection pinning") {
    it("sends every page to the snapshot's base URL with its Bearer even if the store changes") {
      every { http.send(capture(sent)) } answers {
        every { prefs.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns "https://changed.example.com"
        every { tokens.getToken(any()) } returns "changed-token"
        if (pageOf(sent.last()) == 1) response("""[{"id":1}]""", "2") else response("""[{"id":2}]""")
      }

      client.fetchListPages(req(), null, { true }, connection)

      sent shouldHaveSize 2
      sent.forEach {
        it.uri().toString().startsWith("https://pinned.example.com/api/v4/merge_requests?") shouldBe true
        it.headers().firstValue("Authorization").get() shouldBe "Bearer pinned-token"
      }
      verify(exactly = 0) { tokens.getToken(any()) }
    }

    it("without a snapshot captures the connection once and reuses it for every page") {
      every { http.send(capture(sent)) } answers {
        every { prefs.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns "https://changed.example.com"
        every { tokens.getToken(any()) } returns "changed-token"
        if (pageOf(sent.last()) == 1) response("""[{"id":1}]""", "2") else response("""[{"id":2}]""")
      }

      client.fetchListPages(req(), null, { true }, null)

      sent shouldHaveSize 2
      sent.forEach {
        it.uri().toString().startsWith("https://global.example.com/api/v4/merge_requests?") shouldBe true
        it.headers().firstValue("Authorization").get() shouldBe "Bearer global-token"
      }
    }
  }

  describe("query encoding") {
    it("percent-encodes every key and value so none splits into another parameter") {
      every { http.send(capture(sent)) } returns response("[]")
      val search = "a&b=c #+ 日本 %/"

      client.fetchListPages(req(linkedMapOf("search" to search, "not[labels]" to "x,y")), 5, { true }, connection)

      val query = sent.single().uri().rawQuery
      val expectedSearch = URLEncoder.encode(search, StandardCharsets.UTF_8)
      expectedSearch shouldBe "a%26b%3Dc+%23%2B+%E6%97%A5%E6%9C%AC+%25%2F"
      query shouldContain "search=$expectedSearch"
      query shouldContain "not%5Blabels%5D=x%2Cy"
      query shouldBe "search=$expectedSearch&not%5Blabels%5D=x%2Cy&per_page=5&page=1"
    }
  }
})
