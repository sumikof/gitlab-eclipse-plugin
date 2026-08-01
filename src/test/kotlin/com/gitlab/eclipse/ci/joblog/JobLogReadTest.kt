package com.gitlab.eclipse.ci.joblog

import com.gitlab.eclipse.api.GitLabApiException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.http.HttpTimeoutException

class JobLogReadTest : DescribeSpec({
  describe("fetchAndFormatTrace") {
    it("applies strip to the raw trace and returns Loaded with the stripped text") {
      val result = fetchAndFormatTrace({ "RAW" }, { it.lowercase() })

      result shouldBe TraceResult.Loaded("raw")
    }

    it("maps 404 to Failed(http, 404, corr, notCommittal=true) — existence-hiding") {
      val result = fetchAndFormatTrace({ throw GitLabApiException(404, "not-found-body", "corr-404") }, { it })

      result shouldBe TraceResult.Failed("http", 404, "corr-404", notCommittal = true)
    }

    it("maps a non-404 http failure to Failed(http, status, corr, notCommittal=false)") {
      val result = fetchAndFormatTrace({ throw GitLabApiException(403, "forbidden-body", "corr-403") }, { it })

      result shouldBe TraceResult.Failed("http", 403, "corr-403", notCommittal = false)
    }

    it("maps HttpTimeoutException to Failed(timeout)") {
      val result = fetchAndFormatTrace({ throw HttpTimeoutException("timed out") }, { it })

      result shouldBe TraceResult.Failed("timeout", null, null, notCommittal = false)
    }

    it("maps IOException to Failed(io)") {
      val result = fetchAndFormatTrace({ throw IOException("boom") }, { it })

      result shouldBe TraceResult.Failed("io", null, null, notCommittal = false)
    }

    it("rethrows CancellationException — cancellation never becomes a Failure") {
      shouldThrow<CancellationException> {
        fetchAndFormatTrace({ throw CancellationException("cancelled") }, { it })
      }
    }

    it("does not catch an unexpected RuntimeException — it propagates to the caller") {
      shouldThrow<IllegalStateException> {
        fetchAndFormatTrace({ error("unexpected") }, { it })
      }
    }
  }

  describe("readAuditMessage") {
    it("carries action, normalized instance url, project id, job id, and outcome") {
      val message = readAuditMessage(
        action = "displayJobLog",
        instanceUrl = "https://gitlab.example.com/",
        projectId = 7L,
        jobId = 42L,
        outcome = "timeout",
      )

      message shouldBe
        "ciReadAction action=displayJobLog instanceUrl=https://gitlab.example.com projectId=7 jobId=42 outcome=timeout"
    }

    it("appends httpStatus and correlationId only when present") {
      val message = readAuditMessage(
        action = "displayJobLog",
        instanceUrl = "https://gitlab.example.com",
        projectId = 7L,
        jobId = 42L,
        outcome = "http",
        httpStatus = 404,
        correlationId = "corr-1",
      )

      message shouldContain " httpStatus=404"
      message shouldContain " correlationId=corr-1"
    }

    it("never contains token or response-body material (the function has no input for them)") {
      val secretToken = "glpat-SECRET-TOKEN"
      val responseBody = "secret response body"
      // The audit line is built ONLY from the identifiers below; a token or body cannot
      // appear because readAuditMessage has no parameter that could carry one.
      val message = readAuditMessage(
        action = "displayJobLog",
        instanceUrl = "https://gitlab.example.com",
        projectId = 7L,
        jobId = 42L,
        outcome = "http",
        httpStatus = 500,
        correlationId = "corr-2",
      )

      message shouldNotContain secretToken
      message shouldNotContain responseBody
    }
  }
})
