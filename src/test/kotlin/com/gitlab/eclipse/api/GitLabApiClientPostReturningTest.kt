package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.http.GitLabHttpClient
import com.gitlab.eclipse.api.model.GitLabJob
import com.gitlab.eclipse.authentication.GitLabTokenProviderManager
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.preferences.storage.CapturedLog
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** [GitLabApiClient.postReturning]: a pinned body-less POST whose 2xx body is parsed, but never required (L4). */
class GitLabApiClientPostReturningTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val connection = ConnectionSnapshot(
    instanceUrl = "https://pinned.example.com/",
    token = "pinned-token",
    authFingerprint = "0123456789abcdef",
    configGeneration = 42L,
  )

  lateinit var http: GitLabHttpClient
  lateinit var tokens: GitLabTokenProviderManager
  lateinit var prefs: ScopedPreferenceStore

  fun client() = GitLabApiClient(http, tokens, prefs)

  fun response(body: String, status: Int = 201, headers: Map<String, List<String>> = emptyMap()): HttpResponse<String> =
    mockk {
      every { statusCode() } returns status
      every { body() } returns body
      every { headers() } returns HttpHeaders.of(headers) { _, _ -> true }
    }

  beforeEach {
    http = mockk()
    tokens = mockk { every { getToken(any()) } returns "global-token" }
    prefs = mockk { every { getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns "https://global.example.com" }
  }

  it("POSTs to the pinned instance with the pinned Bearer token and an empty body") {
    val captured = slot<HttpRequest>()
    every { http.send(capture(captured)) } returns response("""{"id": 77}""")

    client().postReturning("/projects/1/jobs/9/retry", GitLabJob::class.java, connection)

    captured.captured.method() shouldBe "POST"
    captured.captured.uri().toString() shouldBe "https://pinned.example.com/api/v4/projects/1/jobs/9/retry?"
    captured.captured.headers().firstValue("Authorization").get() shouldBe "Bearer pinned-token"
    captured.captured.bodyPublisher().get().contentLength() shouldBe 0L
    verify(exactly = 0) { prefs.getString(any()) }
    verify(exactly = 0) { tokens.getToken(any()) }
  }

  it("returns the write's status, correlation id and the parsed body") {
    every { http.send(any()) } returns
      response("""{"id": 77, "status": "pending"}""", headers = mapOf("x-request-id" to listOf("corr-1")))

    val posted = client().postReturning("/projects/1/jobs/9/retry", GitLabJob::class.java, connection)

    posted.result shouldBe PostResult(201, "corr-1")
    posted.body?.id shouldBe 77L
    posted.body?.status shouldBe "pending"
  }

  it("treats an empty body as a successful write without a body") {
    every { http.send(any()) } returns response("")

    val posted = client().postReturning("/projects/1/jobs/9/retry", GitLabJob::class.java, connection)

    posted.result shouldBe PostResult(201, null)
    posted.body.shouldBeNull()
  }

  it("treats a body that is not JSON as a successful write without a body, logging the class name only") {
    val log = CapturedLog().apply { install() }
    every { http.send(any()) } returns response("<html>secret-page</html>", status = 200)

    val posted = client().postReturning("/projects/1/jobs/9/retry", GitLabJob::class.java, connection)

    posted.result shouldBe PostResult(200, null)
    posted.body.shouldBeNull()
    log.lines.filter { "secret-page" in it || "pinned-token" in it || "pinned.example.com" in it }.shouldBeEmpty()
    log.contains("JsonSyntaxException") shouldBe true
  }

  it("parses {} to a job whose id is 0, so the caller has to treat id 0 as missing") {
    every { http.send(any()) } returns response("{}")

    val posted = client().postReturning("/projects/1/jobs/9/retry", GitLabJob::class.java, connection)

    posted.body?.id shouldBe 0L
  }

  it("throws GitLabApiException on a non-2xx, like post") {
    every { http.send(any()) } returns
      response("forbidden", status = 403, headers = mapOf("x-request-id" to listOf("corr-403")))

    val e = shouldThrow<GitLabApiException> {
      client().postReturning("/projects/1/jobs/9/retry", GitLabJob::class.java, connection)
    }

    e.statusCode shouldBe 403
    e.correlationId shouldBe "corr-403"
  }
})
