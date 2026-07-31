package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.model.GitLabPipeline
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify

class PipelineServiceTest : DescribeSpec({
  val apiClient = mockk<GitLabApiClient>()
  val service = PipelineService(apiClient)

  // Calls accumulate on the shared mock across tests; verify(exactly = N) below needs each
  // test to start from a clean call history.
  beforeEach { clearMocks(apiClient) }

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

    it("forwards a given connection to fetchObject so the read is pinned to it") {
      val connection = ConnectionSnapshot(
        instanceUrl = "https://pinned.example.com/",
        token = "pinned-token",
        authFingerprint = "0123456789abcdef",
        configGeneration = 42L,
      )
      every {
        apiClient.fetchObject(
          "/projects/42/pipelines",
          mapOf("ref" to "main", "per_page" to "1"),
          Array<GitLabPipeline>::class.java,
          connection,
        )
      } returns emptyArray()

      service.getLatestPipelineForRef("42", "main", connection)

      verify(exactly = 1) {
        apiClient.fetchObject(
          "/projects/42/pipelines",
          mapOf("ref" to "main", "per_page" to "1"),
          Array<GitLabPipeline>::class.java,
          connection,
        )
      }
    }

    it("passes no connection (null) to fetchObject when none is given, keeping the current global behavior") {
      every {
        apiClient.fetchObject(
          "/projects/42/pipelines",
          mapOf("ref" to "main", "per_page" to "1"),
          Array<GitLabPipeline>::class.java,
          null,
        )
      } returns emptyArray()

      service.getLatestPipelineForRef("42", "main")

      verify(exactly = 1) {
        apiClient.fetchObject(
          "/projects/42/pipelines",
          mapOf("ref" to "main", "per_page" to "1"),
          Array<GitLabPipeline>::class.java,
          null,
        )
      }
    }
  }
})
