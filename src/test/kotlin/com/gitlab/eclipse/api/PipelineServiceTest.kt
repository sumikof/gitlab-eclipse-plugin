package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.model.GitLabPipeline
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot

class PipelineServiceTest : DescribeSpec({
  val apiClient = mockk<GitLabApiClient>()
  val service = PipelineService(apiClient)

  describe("getLatestPipelineForRef") {
    it("returns null when the API returns an empty array") {
      val capturedPath = slot<String>()
      val capturedQuery = slot<Map<String, String>>()
      every {
        apiClient.fetchObject(capture(capturedPath), capture(capturedQuery), Array<GitLabPipeline>::class.java)
      } returns emptyArray()

      val result = service.getLatestPipelineForRef("42", "main")

      result shouldBe null
      capturedPath.captured shouldBe "/projects/42/pipelines"
      capturedQuery.captured shouldBe mapOf("ref" to "main", "per_page" to "1")
    }

    it("returns the first element when the API returns multiple pipelines") {
      val first = GitLabPipeline(id = 1, ref = "main", status = "success")
      val second = GitLabPipeline(id = 2, ref = "main", status = "failed")
      every {
        apiClient.fetchObject("/projects/42/pipelines", mapOf("ref" to "main", "per_page" to "1"), Array<GitLabPipeline>::class.java)
      } returns arrayOf(first, second)

      val result = service.getLatestPipelineForRef("42", "main")

      result shouldBe first
    }
  }
})
