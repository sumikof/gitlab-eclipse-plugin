package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.http.GitLabHttpClient
import com.gitlab.eclipse.authentication.GitLabTokenProviderManager
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Covers the GET-path connection pinning added on top of [GitLabApiClient.captureConnection]:
 * an optional [ConnectionSnapshot] threaded through [GitLabApiClient.fetchObject] and
 * [GitLabApiClient.fetchListWithinDeadline] must, when non-null, source the URI base and Bearer
 * credential from the snapshot instead of the live preference store / token manager (design
 * "8B"), and every page of a paginated fetch must reuse the SAME snapshot rather than re-reading
 * the globals per page (design "9B"). `connection = null` must remain byte-for-byte identical to
 * the pre-existing global-reading behavior.
 */
class GitLabApiClientReadPinningTest : DescribeSpec({
  val http = mockk<GitLabHttpClient>()
  val tokens = mockk<GitLabTokenProviderManager>()
  val prefs = mockk<ScopedPreferenceStore>()
  val client = GitLabApiClient(http, tokens, prefs)

  extensions(LoggingKotestExtension)

  data class Item(val id: Long)
  fun req() = ApiRequest("/jobs", emptyMap(), Item::class.java)

  val connection = ConnectionSnapshot(
    instanceUrl = "https://pinned.example.com/",
    token = "pinned-token",
    authFingerprint = "0123456789abcdef",
    configGeneration = 42L,
  )

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
    clearMocks(http, tokens, prefs)
    // Global settings deliberately DIFFERENT from the pinned connection: a pinned read must
    // never fall back to them, otherwise a mid-refresh settings change misroutes the read or
    // sends the wrong credential.
    every { tokens.getToken() } returns "global-token"
    every { prefs.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns "https://global.example.com"
  }

  describe("fetchObject") {
    it("uses the pinned connection's instance and token when given, never the global prefs/token") {
      val captured = slot<HttpRequest>()
      every { http.send(capture(captured)) } returns response("""{"id":1}""")

      client.fetchObject("/user", type = Item::class.java, connection = connection)

      captured.captured.uri().toString() shouldBe "https://pinned.example.com/api/v4/user?"
      captured.captured.headers().firstValue("Authorization").get() shouldBe "Bearer pinned-token"
      verify(exactly = 0) { prefs.getString(any()) }
      verify(exactly = 0) { tokens.getToken() }
    }

    it("falls back to the global preference URL and token when connection is null (backward compat)") {
      val captured = slot<HttpRequest>()
      every { http.send(capture(captured)) } returns response("""{"id":1}""")

      client.fetchObject("/user", type = Item::class.java)

      captured.captured.uri().toString() shouldBe "https://global.example.com/api/v4/user?"
      captured.captured.headers().firstValue("Authorization").get() shouldBe "Bearer global-token"
    }
  }

  describe("fetchListWithinDeadline") {
    it("pins EVERY page to the same connection, ignoring mid-fetch global pref/token changes (design 9B)") {
      val requests = mutableListOf<HttpRequest>()
      val pages = listOf(
        response("""[{"id":1}]""", nextPage = "2"),
        response("""[{"id":2}]""", nextPage = "3"),
        response("""[{"id":3}]""", nextPage = ""),
      )
      var call = 0
      every { http.send(capture(requests)) } answers {
        val page = pages[call]
        call++
        // Mutate the globals BETWEEN pages: a real settings change mid-fetch must not leak
        // into a later page's request when the fetch is pinned to a connection.
        every { prefs.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns "https://changed-$call.example.com"
        every { tokens.getToken() } returns "changed-token-$call"
        page
      }

      val result = client.fetchListWithinDeadline(req(), Duration.ofSeconds(15), connection = connection)

      result shouldBe listOf(Item(1), Item(2), Item(3))
      requests shouldHaveSize 3
      requests.forEach { request ->
        request.uri().toString() shouldStartWith "https://pinned.example.com/api/v4/jobs?"
        request.headers().firstValue("Authorization").get() shouldBe "Bearer pinned-token"
      }
    }

    it("falls back to the global preference URL and token per page when connection is null (backward compat)") {
      every { http.send(any()) } returns response("""[{"id":1}]""")

      val result = client.fetchListWithinDeadline(req(), Duration.ofSeconds(15))

      result shouldBe listOf(Item(1))
      verify(exactly = 1) { tokens.getToken() }
    }
  }

  describe("unpinned GET generation consistency (Codex round-2 P1)") {
    // The unpinned path must go through the same generation-consistent capture as the pinned
    // path: while a settings save is writing the two stores (odd generation), the globals hold
    // the STABLE torn pair (NEW url, OLD token); a plain global read would send the old
    // instance's bearer to the new instance.

    it("unpinned fetchObject never sends the torn (new url, old token) pair while a settings save is in progress") {
      var generationReads = 0
      val genClient = GitLabApiClient(http, tokens, prefs) {
        generationReads++
        if (generationReads <= 3) 1L else 2L // write in progress, then settles
      }
      every { prefs.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns "https://new.example.com"
      every { tokens.getToken() } answers { if (generationReads <= 3) "old-token" else "new-token" }
      val captured = slot<HttpRequest>()
      every { http.send(capture(captured)) } returns response("""{"id":1}""")

      genClient.fetchObject("/user", type = Item::class.java)

      captured.captured.uri().toString() shouldBe "https://new.example.com/api/v4/user?"
      // The old instance's credential must never reach the new instance.
      captured.captured.headers().firstValue("Authorization").get() shouldBe "Bearer new-token"
      verify(exactly = 1) { http.send(any()) }
    }

    it(
      "unpinned fetchObject throws UnstableConnectionException without sending anything " +
        "when the generation stays odd",
    ) {
      val genClient = GitLabApiClient(http, tokens, prefs) { 1L }

      shouldThrow<UnstableConnectionException> {
        genClient.fetchObject("/user", type = Item::class.java)
      }

      verify(exactly = 0) { http.send(any()) }
      verify(exactly = 0) { tokens.getToken() }
      verify(exactly = 0) { prefs.getString(any()) }
    }

    it(
      "unpinned fetchListFromApi throws UnstableConnectionException without sending anything " +
        "when the generation stays odd",
    ) {
      val genClient = GitLabApiClient(http, tokens, prefs) { 1L }

      shouldThrow<UnstableConnectionException> {
        genClient.fetchListFromApi(req())
      }

      verify(exactly = 0) { http.send(any()) }
    }

    it("a pinned fetchObject is unaffected by an odd generation (never consults the capture)") {
      val genClient = GitLabApiClient(http, tokens, prefs) { 1L }
      val captured = slot<HttpRequest>()
      every { http.send(capture(captured)) } returns response("""{"id":1}""")

      genClient.fetchObject("/user", type = Item::class.java, connection = connection)

      captured.captured.uri().toString() shouldBe "https://pinned.example.com/api/v4/user?"
      captured.captured.headers().firstValue("Authorization").get() shouldBe "Bearer pinned-token"
      verify(exactly = 0) { prefs.getString(any()) }
      verify(exactly = 0) { tokens.getToken() }
    }
  }
})
