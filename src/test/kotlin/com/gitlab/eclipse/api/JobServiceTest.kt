package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.model.GitLabJob
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Duration

class JobServiceTest : DescribeSpec({
  val apiClient = mockk<GitLabApiClient>()
  val service = JobService(apiClient)

  describe("getJobsForPipeline") {
    it("returns the jobs fetched via fetchListWithinDeadline for the pipeline's jobs path") {
      val jobs = listOf(
        GitLabJob(id = 1, name = "build", status = "success"),
        GitLabJob(id = 2, name = "test", status = "running"),
      )
      val capturedRequest = slot<ApiRequest<GitLabJob>>()
      val capturedDeadline = slot<Duration>()
      every {
        apiClient.fetchListWithinDeadline(capture(capturedRequest), capture(capturedDeadline))
      } returns jobs

      val result = service.getJobsForPipeline("42", 99L)

      result shouldBe jobs
      capturedRequest.captured.path shouldBe "/projects/42/pipelines/99/jobs"
      capturedRequest.captured.elementType shouldBe GitLabJob::class.java
      capturedDeadline.captured shouldBe Duration.ofSeconds(15)
    }
  }
})
