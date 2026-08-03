package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.model.GitLabMrVersion
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify

class MergeRequestServiceVersionTest : DescribeSpec({
  describe("getLatestMrVersion") {
    it("fetches the versions list then the latest (first) version's full details with diffs") {
      val apiClient = mockk<GitLabApiClient>()
      val service = MergeRequestService(apiClient)
      val captured = slot<ApiRequest<GitLabMrVersion>>()
      val versionsList = listOf(
        GitLabMrVersion(id = 42),
        GitLabMrVersion(id = 10),
      )
      val latestWithDiffs = GitLabMrVersion(
        id = 42,
        headCommitSha = "head42",
        baseCommitSha = "base42",
        startCommitSha = "start42",
        diffs = listOf(
          GitLabMrVersion.Diff(oldPath = "a.txt", newPath = "a.txt"),
        ),
      )
      every { apiClient.fetchListFromApi(capture(captured)) } returns versionsList
      every {
        apiClient.fetchObject(
          "/projects/group%2Fproject/merge_requests/7/versions/42",
          type = GitLabMrVersion::class.java,
        )
      } returns latestWithDiffs

      val result = service.getLatestMrVersion("group%2Fproject", 7)

      result shouldBe latestWithDiffs
      captured.captured.path shouldBe "/projects/group%2Fproject/merge_requests/7/versions"
      captured.captured.elementType shouldBe GitLabMrVersion::class.java
      verify(exactly = 1) {
        apiClient.fetchObject(
          "/projects/group%2Fproject/merge_requests/7/versions/42",
          type = GitLabMrVersion::class.java,
        )
      }
    }

    it("threads a given ConnectionSnapshot into BOTH the versions list fetch and the detail fetch") {
      val apiClient = mockk<GitLabApiClient>()
      val service = MergeRequestService(apiClient)
      val connection = ConnectionSnapshot("https://pinned.example.com", "tok-123", "fp", 1L)
      val latestWithDiffs = GitLabMrVersion(id = 42, headCommitSha = "head42")
      every {
        apiClient.fetchListFromApi(any<ApiRequest<GitLabMrVersion>>(), connection)
      } returns listOf(GitLabMrVersion(id = 42))
      every {
        apiClient.fetchObject(
          "/projects/1/merge_requests/7/versions/42",
          type = GitLabMrVersion::class.java,
          connection = connection,
        )
      } returns latestWithDiffs

      val result = service.getLatestMrVersion("1", 7, connection)

      result shouldBe latestWithDiffs
      verify(exactly = 1) { apiClient.fetchListFromApi(any<ApiRequest<GitLabMrVersion>>(), connection) }
      verify(exactly = 1) {
        apiClient.fetchObject(
          "/projects/1/merge_requests/7/versions/42",
          type = GitLabMrVersion::class.java,
          connection = connection,
        )
      }
    }

    it("returns null and does not fetch the version detail when the versions list is empty") {
      val apiClient = mockk<GitLabApiClient>()
      val service = MergeRequestService(apiClient)
      every { apiClient.fetchListFromApi(any<ApiRequest<GitLabMrVersion>>()) } returns emptyList()

      val result = service.getLatestMrVersion("group%2Fproject", 99)

      result shouldBe null
      verify(exactly = 0) { apiClient.fetchObject(any(), any(), any()) }
    }
  }
})
