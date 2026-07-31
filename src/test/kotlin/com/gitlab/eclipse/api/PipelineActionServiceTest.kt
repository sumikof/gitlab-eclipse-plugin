package com.gitlab.eclipse.api

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class PipelineActionServiceTest : DescribeSpec({
  val apiClient = mockk<GitLabApiClient>()
  val service = PipelineActionService(apiClient)

  val connection = ConnectionSnapshot(
    instanceUrl = "https://pinned.example.com/",
    token = "pinned-token",
    authFingerprint = "0123456789abcdef",
    configGeneration = 42L,
  )

  // Calls accumulate on the shared mock across tests; verify(exactly = N) below needs each
  // test to start from a clean call history.
  beforeEach { clearMocks(apiClient) }

  describe("retry") {
    it("POSTs the retry path with the same connection instance and returns the PostResult") {
      every {
        apiClient.post("/projects/42/pipelines/7/retry", connection = connection)
      } returns PostResult(201, "cid")

      val result = service.retry(connection, 42L, 7L)

      result shouldBe PostResult(201, "cid")
      verify(exactly = 1) { apiClient.post("/projects/42/pipelines/7/retry", connection = connection) }
    }
  }

  describe("cancel") {
    it("POSTs the cancel path with the same connection instance and returns the PostResult") {
      every {
        apiClient.post("/projects/42/pipelines/7/cancel", connection = connection)
      } returns PostResult(200, null)

      val result = service.cancel(connection, 42L, 7L)

      result shouldBe PostResult(200, null)
      verify(exactly = 1) { apiClient.post("/projects/42/pipelines/7/cancel", connection = connection) }
    }
  }

  describe("path assembly") {
    it("produces different last path segments for retry vs cancel (guards a copy-paste typo)") {
      val recordedPaths = mutableListOf<String>()
      every { apiClient.post(any(), connection = connection) } answers {
        recordedPaths.add(firstArg())
        PostResult(200, null)
      }

      service.retry(connection, 42L, 7L)
      service.cancel(connection, 42L, 7L)

      recordedPaths shouldBe listOf(
        "/projects/42/pipelines/7/retry",
        "/projects/42/pipelines/7/cancel",
      )
    }
  }
})
