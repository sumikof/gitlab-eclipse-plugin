package com.gitlab.eclipse.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class JobTraceServiceTest : DescribeSpec({
  val apiClient = mockk<GitLabApiClient>()
  val service = JobTraceService(apiClient)

  val connection = ConnectionSnapshot(
    instanceUrl = "https://x.example/",
    token = "tok",
    authFingerprint = "fp",
    configGeneration = 1L,
  )

  beforeEach { clearMocks(apiClient) }

  describe("getTrace") {
    it("builds the correct path and passes the connection through") {
      every { apiClient.fetchText(any(), any()) } returns "raw log"

      service.getTrace(projectId = 42L, jobId = 9L, connection = connection)

      verify(exactly = 1) { apiClient.fetchText("/projects/42/jobs/9/trace", connection) }
    }

    it("returns the client's body verbatim") {
      every { apiClient.fetchText(any(), any()) } returns "raw log"

      val result = service.getTrace(projectId = 42L, jobId = 9L, connection = connection)

      result shouldBe "raw log"
    }

    it("propagates exceptions from the API client") {
      every { apiClient.fetchText(any(), any()) } throws GitLabApiException(404, "not found", "corr-1")

      shouldThrow<GitLabApiException> { service.getTrace(1L, 2L, connection) }
    }
  }
})
