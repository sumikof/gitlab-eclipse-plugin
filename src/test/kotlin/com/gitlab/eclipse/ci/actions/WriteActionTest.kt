package com.gitlab.eclipse.ci.actions

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.PostResult
import com.gitlab.eclipse.api.UnstableConnectionException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.http.HttpTimeoutException

class WriteActionTest : DescribeSpec({
  describe("normalizeInstanceUrl") {
    it("strips trailing slashes and leaves other urls untouched") {
      normalizeInstanceUrl("https://gitlab.example.com/") shouldBe "https://gitlab.example.com"
      normalizeInstanceUrl("https://gitlab.example.com") shouldBe "https://gitlab.example.com"
    }
  }

  describe("classifyWrite") {
    it("maps a successful call to Success carrying the PostResult") {
      val result = PostResult(httpStatus = 201, correlationId = "corr-1")

      classifyWrite { result } shouldBe WriteOutcome.Success(result)
    }

    it("maps GitLabApiException to Failure(status, correlationId, http)") {
      val outcome = classifyWrite { throw GitLabApiException(403, "forbidden-body", "corr-403") }

      outcome shouldBe WriteOutcome.Failure(httpStatus = 403, correlationId = "corr-403", failureKind = "http")
    }

    it("maps HttpTimeoutException to Failure(null, null, timeout)") {
      val outcome = classifyWrite { throw HttpTimeoutException("request timed out") }

      outcome shouldBe WriteOutcome.Failure(httpStatus = null, correlationId = null, failureKind = "timeout")
    }

    it("maps IOException to Failure(null, null, io)") {
      val outcome = classifyWrite { throw IOException("connection reset") }

      outcome shouldBe WriteOutcome.Failure(httpStatus = null, correlationId = null, failureKind = "io")
    }

    it("rethrows CancellationException instead of classifying it") {
      shouldThrow<CancellationException> {
        classifyWrite { throw CancellationException("cancelled") }
      }
    }
  }

  describe("pinnedConnectionFor") {
    val apiClient = mockk<GitLabApiClient>()
    val nodeUrl = "https://gitlab.example.com/"
    val nodeFingerprint = "fp-node"

    beforeEach { clearMocks(apiClient) }

    it("returns the captured snapshot when url (trailing-slash normalized) and fingerprint match") {
      val snapshot = ConnectionSnapshot(
        instanceUrl = "https://gitlab.example.com",
        token = "secret-token",
        authFingerprint = "fp-node",
        configGeneration = 1L,
      )
      every { apiClient.captureConnection() } returns snapshot

      pinnedConnectionFor(apiClient, nodeUrl, nodeFingerprint) shouldBe snapshot
    }

    it("returns null when the instance url changed (FR-8)") {
      every { apiClient.captureConnection() } returns ConnectionSnapshot(
        instanceUrl = "https://other.example.com",
        token = "secret-token",
        authFingerprint = "fp-node",
        configGeneration = 2L,
      )

      pinnedConnectionFor(apiClient, nodeUrl, nodeFingerprint).shouldBeNull()
    }

    it("returns null when the auth fingerprint changed on the same url (9A)") {
      every { apiClient.captureConnection() } returns ConnectionSnapshot(
        instanceUrl = "https://gitlab.example.com",
        token = "other-token",
        authFingerprint = "fp-other-account",
        configGeneration = 3L,
      )

      pinnedConnectionFor(apiClient, nodeUrl, nodeFingerprint).shouldBeNull()
    }

    it("returns null when the connection is unstable (capture throws)") {
      every { apiClient.captureConnection() } throws UnstableConnectionException()

      pinnedConnectionFor(apiClient, nodeUrl, nodeFingerprint).shouldBeNull()
    }
  }

  describe("writeAuditMessage") {
    val sentinelToken = "glpat-SENTINEL-TOKEN"
    val sentinelBody = "SENTINEL-RAW-RESPONSE-BODY"

    it("on success includes normalized url, action, projectId, target, outcome, status, and correlationId") {
      val outcome = WriteOutcome.Success(PostResult(httpStatus = 201, correlationId = "corr-1"))

      val message =
        writeAuditMessage("retry", "https://gitlab.example.com/", 1234L, "pipeline", 555L, outcome)

      message shouldContain "https://gitlab.example.com"
      message shouldNotContain "https://gitlab.example.com/ "
      message shouldContain "retry"
      message shouldContain "1234"
      message shouldContain "pipeline"
      message shouldContain "555"
      message shouldContain "success"
      message shouldContain "201"
      message shouldContain "corr-1"
    }

    it("on http failure includes the status and correlationId and never the raw body or token") {
      // Classify a real API exception carrying a sentinel body: the audit line must carry
      // only status + correlationId, never the body (AC-8).
      val outcome = classifyWrite {
        val snapshot = ConnectionSnapshot("https://gitlab.example.com", sentinelToken, "fp", 1L)
        check(snapshot.token == sentinelToken) // the token exists in scope; it must not reach the message
        throw GitLabApiException(403, sentinelBody, "corr-403")
      }

      val message = writeAuditMessage("cancel", "https://gitlab.example.com", 1234L, "job", 777L, outcome)

      message shouldContain "failure"
      message shouldContain "403"
      message shouldContain "corr-403"
      message shouldContain "cancel"
      message shouldContain "job"
      message shouldContain "777"
      message shouldNotContain sentinelBody
      message shouldNotContain sentinelToken
    }

    it("on timeout failure still includes the normalized instance url with no status or correlationId values") {
      val outcome = WriteOutcome.Failure(httpStatus = null, correlationId = null, failureKind = "timeout")

      val message = writeAuditMessage("play", "https://gitlab.example.com/", 9L, "job", 11L, outcome)

      message shouldContain "https://gitlab.example.com"
      message shouldContain "timeout"
      message shouldContain "failure"
      message shouldNotContain "null"
    }
  }
})
