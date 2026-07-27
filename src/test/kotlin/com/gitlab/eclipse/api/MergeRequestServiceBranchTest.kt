package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot

class MergeRequestServiceBranchTest : DescribeSpec({
  val apiClient = mockk<GitLabApiClient>()
  val service = MergeRequestService(apiClient)

  describe("findOpenMrsForBranch") {
    it("requests open merge requests globally for the given source branch") {
      val captured = slot<ApiRequest<GitLabMergeRequest>>()
      val expected = listOf(
        GitLabMergeRequest(1, 1, "A", 1, "https://x/1", "opened"),
        GitLabMergeRequest(2, 2, "B", 2, "https://x/2", "opened"),
      )
      every { apiClient.fetchListFromApi(capture(captured)) } returns expected

      val result = service.findOpenMrsForBranch("feature/foo")

      result shouldBe expected
      captured.captured.path shouldBe "/merge_requests"
      captured.captured.query shouldBe mapOf(
        "scope" to "all",
        "state" to "opened",
        "source_branch" to "feature/foo",
      )
      captured.captured.elementType shouldBe GitLabMergeRequest::class.java
    }
  }

  describe("getClosesIssues") {
    it("requests the issues closed by the given merge request") {
      val captured = slot<ApiRequest<GitLabIssue>>()
      val expected = listOf(
        GitLabIssue(1, 1, "Issue A", "https://x/issues/1", "opened", null),
      )
      every { apiClient.fetchListFromApi(capture(captured)) } returns expected

      val result = service.getClosesIssues("group%2Fproject", 42)

      result shouldBe expected
      captured.captured.path shouldBe "/projects/group%2Fproject/merge_requests/42/closes_issues"
      captured.captured.query shouldBe emptyMap()
      captured.captured.elementType shouldBe GitLabIssue::class.java
    }
  }
})
