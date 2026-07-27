package com.gitlab.eclipse.mergerequests

import com.gitlab.eclipse.api.MergeRequestService
import com.gitlab.eclipse.api.ProjectDetailService
import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.api.model.GitLabProject
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

private const val REPO_PROJECT_ID = 42L
private const val ENCODED_PROJECT_ID = "grp%2Fproj"

class CurrentBranchMrLookupTest : DescribeSpec({
  val mrService = mockk<MergeRequestService>()
  val projectDetail = mockk<ProjectDetailService>()

  fun lookup() = CurrentBranchMrLookup(mrService, projectDetail)

  fun context(remoteName: String = "origin") = RepositoryContext(
    gitDir = "/repo/.git",
    workTree = "/repo",
    namespaceWithPath = "grp/proj",
    instanceUrl = "https://gitlab.example.com",
    webUrl = "https://gitlab.example.com/grp/proj",
    remoteName = remoteName,
    projectId = ENCODED_PROJECT_ID,
  )

  fun branch(
    name: String? = "feature",
    trackingBranch: String? = null,
    hasUpstream: Boolean = trackingBranch != null,
    upstreamRemote: String? = null,
    headSha: String? = "abc123",
  ) = CurrentBranch(name, trackingBranch, hasUpstream, upstreamRemote, headSha)

  fun mr(
    id: Long = 1L,
    iid: Long = 10L,
    projectId: Long = REPO_PROJECT_ID,
    sourceProjectId: Long? = REPO_PROJECT_ID,
    sha: String? = "abc123",
    updatedAt: String? = "2026-01-01T00:00:00.000Z",
  ) = GitLabMergeRequest(
    id = id,
    iid = iid,
    title = "title",
    projectId = projectId,
    webUrl = "https://gitlab.example.com/grp/proj/-/merge_requests/$iid",
    state = "opened",
    draft = false,
    sourceProjectId = sourceProjectId,
    targetProjectId = REPO_PROJECT_ID,
    sourceBranch = "feature",
    sha = sha,
    updatedAt = updatedAt,
  )

  beforeEach {
    every { projectDetail.getProject(ENCODED_PROJECT_ID) } returns GitLabProject(id = REPO_PROJECT_ID)
  }

  afterEach { clearAllMocks(answers = false) }

  describe("lookup") {
    it("uses the tracking branch name when its remote matches the context remote") {
      every { mrService.findOpenMrsForBranch("upstream-name") } returns emptyList()

      lookup().lookup(
        context(remoteName = "origin"),
        branch(name = "feature", trackingBranch = "upstream-name", upstreamRemote = "origin"),
      )

      verify(exactly = 1) { mrService.findOpenMrsForBranch("upstream-name") }
    }

    it("falls back to the local branch name when tracking points at a different remote") {
      every { mrService.findOpenMrsForBranch("feature") } returns emptyList()

      lookup().lookup(
        context(remoteName = "origin"),
        branch(name = "feature", trackingBranch = "upstream-name", upstreamRemote = "fork"),
      )

      verify(exactly = 1) { mrService.findOpenMrsForBranch("feature") }
    }

    it("falls back to the local branch name when there is no tracking branch") {
      every { mrService.findOpenMrsForBranch("feature") } returns emptyList()

      lookup().lookup(
        context(remoteName = "origin"),
        branch(name = "feature", trackingBranch = null, upstreamRemote = null),
      )

      verify(exactly = 1) { mrService.findOpenMrsForBranch("feature") }
    }

    it("returns empty and makes no service calls for a detached HEAD (null branch name)") {
      val result = lookup().lookup(context(), branch(name = null))

      result shouldBe CurrentBranchInfo(null, emptyList())
      verify(exactly = 0) { projectDetail.getProject(any()) }
      verify(exactly = 0) { mrService.findOpenMrsForBranch(any()) }
    }

    it("filters out fork MRs whose source_project_id does not match the selected repo project") {
      val forkMr = mr(id = 1L, sourceProjectId = 99L)
      val ownMr = mr(id = 2L, sourceProjectId = REPO_PROJECT_ID)
      every { mrService.findOpenMrsForBranch("feature") } returns listOf(forkMr, ownMr)
      every { mrService.getClosesIssues(any(), any()) } returns emptyList()

      val result = lookup().lookup(context(), branch())

      result.mr shouldBe ownMr
    }

    it("prefers the MR whose sha matches the branch head sha among multiple candidates") {
      val older = mr(id = 1L, sha = "other-sha", updatedAt = "2026-06-01T00:00:00.000Z")
      val headMatch = mr(id = 2L, sha = "abc123", updatedAt = "2020-01-01T00:00:00.000Z")
      every { mrService.findOpenMrsForBranch("feature") } returns listOf(older, headMatch)
      every { mrService.getClosesIssues(any(), any()) } returns emptyList()

      val result = lookup().lookup(context(), branch(headSha = "abc123"))

      result.mr shouldBe headMatch
    }

    it("without a headSha match, picks the most recently updated candidate") {
      val stale = mr(id = 1L, sha = "no-match-1", updatedAt = "2020-01-01T00:00:00.000Z")
      val fresh = mr(id = 2L, sha = "no-match-2", updatedAt = "2026-06-01T00:00:00.000Z")
      every { mrService.findOpenMrsForBranch("feature") } returns listOf(stale, fresh)
      every { mrService.getClosesIssues(any(), any()) } returns emptyList()

      val result = lookup().lookup(context(), branch(headSha = "does-not-match"))

      result.mr shouldBe fresh
    }

    it("returns empty when no candidate survives the source_project_id filter") {
      every { mrService.findOpenMrsForBranch("feature") } returns listOf(mr(sourceProjectId = 99L))

      val result = lookup().lookup(context(), branch())

      result shouldBe CurrentBranchInfo(null, emptyList())
    }

    it("fetches closes_issues using the MR's own (target) project id, not the context project id") {
      val theMr = mr(projectId = 777L)
      val issue = GitLabIssue(
        id = 1L,
        iid = 5L,
        title = "an issue",
        webUrl = "https://gitlab.example.com/grp/proj/-/issues/5",
        state = "opened",
        references = null,
      )
      every { mrService.findOpenMrsForBranch("feature") } returns listOf(theMr)
      every { mrService.getClosesIssues("777", theMr.iid) } returns listOf(issue)

      val result = lookup().lookup(context(), branch())

      result shouldBe CurrentBranchInfo(theMr, listOf(issue))
      verify(exactly = 1) { mrService.getClosesIssues("777", theMr.iid) }
    }
  }
})
