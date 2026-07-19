package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.model.GitLabIssue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot

class IssueServiceTest : DescribeSpec({
  val apiClient = mockk<GitLabApiClient>()
  val service = IssueService(apiClient)

  describe("getIssuesAssignedToMe") {
    it("requests open issues assigned to me and de-dupes by id") {
      val captured = slot<ApiRequest<GitLabIssue>>()
      every { apiClient.fetchListFromApi(capture(captured)) } returns listOf(
        GitLabIssue(1, 11, "A", "https://x/1", "opened", null),
        GitLabIssue(1, 11, "A", "https://x/1", "opened", null), // duplicate across page boundary
        GitLabIssue(2, 12, "B", "https://x/2", "opened", null),
      )

      val result = service.getIssuesAssignedToMe()

      result.map { it.id } shouldBe listOf(1L, 2L)
      captured.captured.path shouldBe "/issues"
      captured.captured.query shouldBe mapOf("scope" to "assigned_to_me", "state" to "opened")
      captured.captured.elementType shouldBe GitLabIssue::class.java
    }
  }
})
