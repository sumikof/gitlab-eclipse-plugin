package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.model.GitLabJob
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Duration

class JobServiceTest : DescribeSpec({
  val apiClient = mockk<GitLabApiClient>()
  val service = JobService(apiClient)

  // Calls accumulate on the shared mock across tests; verify(exactly = N) below needs each
  // test to start from a clean call history.
  beforeEach { clearMocks(apiClient) }

  describe("getJobsForPipeline") {
    it("returns the jobs fetched via fetchListWithinDeadline for the pipeline's jobs path") {
      val jobs = listOf(
        GitLabJob(id = 1, name = "build", status = "success"),
        GitLabJob(id = 2, name = "test", status = "running"),
      )
      val capturedRequest = slot<ApiRequest<GitLabJob>>()
      val capturedDeadline = slot<Duration>()
      every {
        apiClient.fetchListWithinDeadline(capture(capturedRequest), capture(capturedDeadline), any(), any())
      } returns jobs

      val result = service.getJobsForPipeline("42", 99L)

      result shouldBe jobs
      capturedRequest.captured.path shouldBe "/projects/42/pipelines/99/jobs"
      capturedRequest.captured.elementType shouldBe GitLabJob::class.java
      capturedDeadline.captured shouldBe Duration.ofSeconds(15)
    }

    it("threads the caller's isActive through to fetchListWithinDeadline") {
      val capturedIsActive = slot<() -> Boolean>()
      every {
        apiClient.fetchListWithinDeadline(any<ApiRequest<GitLabJob>>(), any(), any(), capture(capturedIsActive))
      } returns emptyList()

      var active = true
      service.getJobsForPipeline("42", 99L, isActive = { active })

      capturedIsActive.captured() shouldBe true
      active = false
      capturedIsActive.captured() shouldBe false
    }

    it("forwards a given connection to fetchListWithinDeadline so every page is pinned to it") {
      val connection = ConnectionSnapshot(
        instanceUrl = "https://pinned.example.com/",
        token = "pinned-token",
        authFingerprint = "0123456789abcdef",
        configGeneration = 42L,
      )
      val capturedConnection = slot<ConnectionSnapshot>()
      every {
        apiClient.fetchListWithinDeadline(any<ApiRequest<GitLabJob>>(), any(), any(), any(), capture(capturedConnection))
      } returns emptyList()

      service.getJobsForPipeline("42", 99L, connection = connection)

      capturedConnection.captured shouldBe connection
    }

    it("passes no connection (null) to fetchListWithinDeadline when none is given (backward compat)") {
      every {
        apiClient.fetchListWithinDeadline(any<ApiRequest<GitLabJob>>(), any(), any(), any(), null)
      } returns emptyList()

      service.getJobsForPipeline("42", 99L, isActive = { true })

      verify(exactly = 1) {
        apiClient.fetchListWithinDeadline(any<ApiRequest<GitLabJob>>(), any(), any(), any(), null)
      }
    }

    it("adds no query parameter by default") {
      val request = slot<ApiRequest<GitLabJob>>()
      every {
        apiClient.fetchListWithinDeadline(capture(request), any(), any(), any())
      } returns emptyList()

      service.getJobsForPipeline("42", 99L)

      request.captured.query shouldBe emptyMap()
    }

    it("adds include_retried=true when includeRetried is set, keeping path and connection pinning") {
      val connection = ConnectionSnapshot(
        instanceUrl = "https://pinned.example.com/",
        token = "pinned-token",
        authFingerprint = "0123456789abcdef",
        configGeneration = 42L,
      )
      val request = slot<ApiRequest<GitLabJob>>()
      val conn = slot<ConnectionSnapshot>()
      every {
        apiClient.fetchListWithinDeadline(capture(request), any(), any(), any(), capture(conn))
      } returns emptyList()

      service.getJobsForPipeline("42", 99L, connection = connection, includeRetried = true)

      request.captured.path shouldBe "/projects/42/pipelines/99/jobs"
      request.captured.query shouldBe mapOf("include_retried" to "true")
      conn.captured shouldBe connection
    }

    it("propagates GitLabApiException 403 and 404 unchanged") {
      for (status in listOf(403, 404)) {
        val ex = GitLabApiException(status, "")
        every {
          apiClient.fetchListWithinDeadline(any<ApiRequest<GitLabJob>>(), any(), any(), any(), any())
        } throws ex
        io.kotest.assertions.throwables.shouldThrow<GitLabApiException> {
          service.getJobsForPipeline("42", 99L)
        } shouldBe ex
      }
    }
  }

  describe("getJob") {
    it("fetches the single-job path pinned to the given connection") {
      val connection = ConnectionSnapshot(
        instanceUrl = "https://pinned.example.com/",
        token = "pinned-token",
        authFingerprint = "0123456789abcdef",
        configGeneration = 42L,
      )
      val job = GitLabJob(id = 7, status = "running", startedAt = "2024-01-01T00:00:00Z")
      every {
        apiClient.fetchObject("/projects/5/jobs/7", emptyMap(), GitLabJob::class.java, connection)
      } returns job

      service.getJob(5L, 7L, connection) shouldBe job
    }
  }
})
