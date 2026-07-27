package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.model.GitLabMergeRequest
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot

class MergeRequestServiceTest : DescribeSpec({
  val apiClient = mockk<GitLabApiClient>()
  val service = MergeRequestService(apiClient)

  describe("getMergeRequestsAssignedToMe") {
    it("requests open merge requests assigned to me and de-dupes by id") {
      val captured = slot<ApiRequest<GitLabMergeRequest>>()
      every { apiClient.fetchListFromApi(capture(captured)) } returns listOf(
        GitLabMergeRequest(1, 1, "A", 1, "https://x/1", "opened"),
        GitLabMergeRequest(1, 1, "A", 1, "https://x/1", "opened"), // duplicate across page boundary
        GitLabMergeRequest(2, 2, "B", 1, "https://x/2", "opened"),
      )

      val result = service.getMergeRequestsAssignedToMe()

      result.map { it.id } shouldBe listOf(1L, 2L)
      captured.captured.path shouldBe "/merge_requests"
      captured.captured.query shouldBe mapOf("scope" to "assigned_to_me", "state" to "opened")
      captured.captured.elementType shouldBe GitLabMergeRequest::class.java
    }
  }
})
