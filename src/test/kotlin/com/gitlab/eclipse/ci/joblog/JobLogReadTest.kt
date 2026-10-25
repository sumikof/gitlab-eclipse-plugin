package com.gitlab.eclipse.ci.joblog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class JobLogReadTest : DescribeSpec({
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

  describe("jobLogAuditMessage") {
    it("has the fixed shape without an instance URL") {
      jobLogAuditMessage("displayJobLog", 7L, 42L, "loaded") shouldBe
        "ciReadAction action=displayJobLog projectId=7 jobId=42 outcome=loaded"
    }

    it("appends httpStatus, correlationId and exceptionClass only when present, in that order") {
      jobLogAuditMessage("saveRawLog", 7L, 42L, "http", 404, "corr-1", "GitLabApiException") shouldBe
        "ciReadAction action=saveRawLog projectId=7 jobId=42 outcome=http " +
        "httpStatus=404 correlationId=corr-1 exceptionClass=GitLabApiException"
      jobLogAuditMessage("saveRawLog", null, 42L, "io", exceptionClass = "IOException") shouldBe
        "ciReadAction action=saveRawLog projectId=null jobId=42 outcome=io exceptionClass=IOException"
    }

    it("has no parameter that could carry a URL, path, body or credential") {
      ::jobLogAuditMessage.parameters.map { it.name } shouldBe
        listOf("action", "projectId", "jobId", "outcome", "httpStatus", "correlationId", "exceptionClass")
    }
  }
})
