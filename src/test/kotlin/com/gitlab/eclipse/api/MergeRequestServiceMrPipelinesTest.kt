package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.model.GitLabPipeline
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot

class MergeRequestServiceMrPipelinesTest : DescribeSpec({
  describe("getMrPipelines") {
    it("reads one page of 100 from the MR pipelines path, in server order") {
      val apiClient = mockk<GitLabApiClient>()
      val service = MergeRequestService(apiClient)
      val path = slot<String>()
      val query = slot<Map<String, String>>()
      val conn = slot<ConnectionSnapshot?>()
      val first = GitLabPipeline(id = 9, projectId = 5)
      val second = GitLabPipeline(id = 3)
      every {
        apiClient.fetchObject(capture(path), capture(query), Array<GitLabPipeline>::class.java, captureNullable(conn))
      } returns arrayOf(first, second)

      service.getMrPipelines(5L, 7L) shouldBe listOf(first, second)

      path.captured shouldBe "/projects/5/merge_requests/7/pipelines"
      query.captured shouldBe mapOf("per_page" to "100")
      conn.captured shouldBe null
    }

    it("pins the given connection") {
      val apiClient = mockk<GitLabApiClient>()
      val service = MergeRequestService(apiClient)
      val connection = ConnectionSnapshot(
        instanceUrl = "https://pinned.example.com/",
        token = "pinned-token",
        authFingerprint = "0123456789abcdef",
        configGeneration = 42L,
      )
      val conn = slot<ConnectionSnapshot?>()
      every {
        apiClient.fetchObject(any(), any(), Array<GitLabPipeline>::class.java, captureNullable(conn))
      } returns emptyArray()

      service.getMrPipelines(5L, 7L, connection) shouldBe emptyList()

      conn.captured shouldBe connection
    }

    it("propagates GitLabApiException 403 and 404 unchanged") {
      val apiClient = mockk<GitLabApiClient>()
      val service = MergeRequestService(apiClient)
      for (status in listOf(403, 404)) {
        val ex = GitLabApiException(status, "")
        every {
          apiClient.fetchObject(any(), any(), Array<GitLabPipeline>::class.java, any())
        } throws ex
        shouldThrow<GitLabApiException> { service.getMrPipelines(5L, 7L) } shouldBe ex
      }
    }
  }
})
