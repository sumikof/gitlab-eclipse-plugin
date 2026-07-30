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
import io.mockk.verify
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

class GitLabApiClientDeadlineTest : DescribeSpec({
  val http = mockk<GitLabHttpClient>()
  val tokens = mockk<GitLabTokenProviderManager>()
  val prefs = mockk<ScopedPreferenceStore>(relaxed = true)
  val client = GitLabApiClient(http, tokens, prefs)

  extensions(LoggingKotestExtension)

  data class Item(val id: Long)
  fun req() = ApiRequest("/jobs", emptyMap(), Item::class.java)

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

  val deadline = Duration.ofSeconds(15)
  val fiveSecondsNanos = Duration.ofSeconds(5).toNanos()
  val tenSecondsNanos = Duration.ofSeconds(10).toNanos()

  describe("fetchListWithinDeadline") {
    it("returns in a single call when there is no x-next-page") {
      every { http.send(any()) } returns response("""[{"id":1},{"id":2}]""")
      val clock = { 0L }

      val result = client.fetchListWithinDeadline(req(), deadline, clock)

      result shouldBe listOf(Item(1), Item(2))
      verify(exactly = 1) { http.send(any()) }
    }

    it("aggregates all pages when the elapsed time between pages stays under the deadline") {
      var nanos = 0L
      val clock = { nanos }
      val pages = listOf(
        response("""[{"id":1}]""", nextPage = "2"),
        response("""[{"id":2}]""", nextPage = ""),
      )
      var call = 0
      every { http.send(any()) } answers {
        val page = pages[call]
        call++
        nanos += fiveSecondsNanos
        page
      }

      val result = client.fetchListWithinDeadline(req(), deadline, clock)

      result shouldBe listOf(Item(1), Item(2))
      verify(exactly = 2) { http.send(any()) }
    }

    it("throws GitLabApiTimeoutException when the clock exceeds the deadline before fetching the next page") {
      var nanos = 0L
      val clock = { nanos }
      val pages = listOf(
        response("""[{"id":1}]""", nextPage = "2"),
        response("""[{"id":2}]""", nextPage = "3"),
      )
      var call = 0
      every { http.send(any()) } answers {
        val page = pages[call]
        call++
        nanos += tenSecondsNanos
        page
      }

      val exception = shouldThrow<GitLabApiTimeoutException> {
        client.fetchListWithinDeadline(req(), deadline, clock)
      }

      exception.page shouldBe 3
      // page 1 fetched at t=0 (0 <= 15s), page 2 fetched at t=10s (10s <= 15s);
      // the check before page 3 sees t=20s > 15s and throws without a third HTTP call.
      verify(exactly = 2) { http.send(any()) }
    }

    it("throws CancellationException before fetching the next page once isActive returns false") {
      var call = 0
      val pages = listOf(
        response("""[{"id":1}]""", nextPage = "2"),
        response("""[{"id":2}]""", nextPage = ""),
      )
      every { http.send(any()) } answers {
        val page = pages[call]
        call++
        page
      }

      shouldThrow<CancellationException> {
        client.fetchListWithinDeadline(req(), deadline, { 0L }, isActive = { call == 0 })
      }

      // page 1 fetched while isActive was still true; the check before page 2 sees
      // isActive == false and throws without a second HTTP call.
      verify(exactly = 1) { http.send(any()) }
    }

    it(
      "throws GitLabApiTimeoutException (not IllegalArgumentException) when elapsed lands exactly " +
        "on the deadline at the top of an iteration",
    ) {
      var nanos = 0L
      val clock = { nanos }
      val pages = listOf(
        response("""[{"id":1}]""", nextPage = "2"),
      )
      var call = 0
      every { http.send(any()) } answers {
        val page = pages[call]
        call++
        // After fetching page 1, the clock jumps to exactly the deadline. A
        // two-read implementation (elapsed check, then remaining computation)
        // would read this same value twice, compute remainingNanos == 0, and
        // blow up with IllegalArgumentException from HttpRequest.timeout()
        // instead of the intended GitLabApiTimeoutException.
        nanos = deadline.toNanos()
        page
      }

      val exception = shouldThrow<GitLabApiTimeoutException> {
        client.fetchListWithinDeadline(req(), deadline, clock)
      }

      exception.page shouldBe 2
      // only page 1 was fetched; the deadline check before page 2 throws
      // without issuing a second HTTP call.
      verify(exactly = 1) { http.send(any()) }
    }

    it("caps each page's request timeout to the remaining deadline budget") {
      val bigDeadline = Duration.ofSeconds(60)
      var nanos = 0L
      val clock = { nanos }
      val pages = listOf(
        response("""[{"id":1}]""", nextPage = "2"),
        response("""[{"id":2}]""", nextPage = ""),
      )
      var call = 0
      val requests = mutableListOf<HttpRequest>()
      every { http.send(capture(requests)) } answers {
        val page = pages[call]
        call++
        nanos += Duration.ofSeconds(40).toNanos()
        page
      }

      val result = client.fetchListWithinDeadline(req(), bigDeadline, clock)

      result shouldBe listOf(Item(1), Item(2))
      // page 1 fetched at t=0: remaining budget is the full 60s deadline, so the
      // request timeout is capped at the 30s default (min(30s, 60s) == 30s).
      requests[0].timeout().get() shouldBe Duration.ofSeconds(30)
      // page 2 fetched at t=40s: remaining budget is 60s - 40s = 20s, which is
      // less than the 30s default, so the request timeout is capped to 20s.
      requests[1].timeout().get() shouldBe Duration.ofSeconds(20)
    }
  }
})
