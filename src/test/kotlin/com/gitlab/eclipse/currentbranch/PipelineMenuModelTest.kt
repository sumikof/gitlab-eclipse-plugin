package com.gitlab.eclipse.currentbranch

import com.gitlab.eclipse.api.model.GitLabJob
import com.gitlab.eclipse.api.model.GitLabPipeline
import com.gitlab.eclipse.ci.PipelineKind
import com.gitlab.eclipse.views.sidebar.AmbiguousTags
import com.gitlab.eclipse.views.sidebar.CurrentBranchSectionInput
import com.gitlab.eclipse.views.sidebar.NoPipeline
import com.gitlab.eclipse.views.sidebar.NoRepository
import com.gitlab.eclipse.views.sidebar.PipelineShown
import com.gitlab.eclipse.views.sidebar.PipelineSide
import com.gitlab.eclipse.views.sidebar.PipelineSnapshot
import com.gitlab.eclipse.views.sidebar.Resolved
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** Status-bar design §4.6 / R12 / R14: which pulldown entries exist and when they are enabled. */
class PipelineMenuModelTest : StringSpec({
  fun snap(input: CurrentBranchSectionInput?) = CurrentBranchSnapshot(1, "/g", "/w", input, false)

  fun shown(
    status: String? = "success",
    kind: PipelineKind = PipelineKind.BRANCH,
    projectId: Long? = 42,
    webUrl: String? = "https://gl.example.com/p/1",
    jobs: Result<List<GitLabJob>> = Result.success(emptyList()),
  ) = Resolved(
    null,
    Result.success(
      PipelineShown(
        PipelineSnapshot(
          GitLabPipeline(id = 1, projectId = projectId, status = status, webUrl = webUrl),
          kind,
          jobs,
          Result.success(emptyList()),
          "https://gl.example.com",
          "fp",
        ),
      ),
    ),
  )

  fun resolved(side: PipelineSide) = Resolved(null, Result.success(side))

  fun kinds(input: CurrentBranchSectionInput?) = PipelineMenuModel.build(snap(input)).map { it.kind }

  fun entry(input: CurrentBranchSectionInput?, kind: MenuEntryKind) =
    PipelineMenuModel.build(snap(input)).single { it.kind == kind }

  "a shown branch pipeline offers view, retry, cancel, create and refresh in order" {
    kinds(shown()) shouldBe listOf(
      MenuEntryKind.VIEW,
      MenuEntryKind.RETRY,
      MenuEntryKind.CANCEL,
      MenuEntryKind.CREATE,
      MenuEntryKind.REFRESH,
    )
  }

  "retry and cancel follow the CiStatus context action" {
    val retryable = listOf("success", "failed", "canceled", "canceling")
    val cancellable = listOf("created", "waiting_for_resource", "preparing", "pending", "scheduled", "running")
    val neither = listOf("manual", "skipped", "bogus", null)
    retryable.forEach {
      entry(shown(it), MenuEntryKind.RETRY).enabled shouldBe true
      entry(shown(it), MenuEntryKind.CANCEL).enabled shouldBe false
    }
    cancellable.forEach {
      entry(shown(it), MenuEntryKind.RETRY).enabled shouldBe false
      entry(shown(it), MenuEntryKind.CANCEL).enabled shouldBe true
    }
    neither.forEach {
      entry(shown(it), MenuEntryKind.RETRY).enabled shouldBe false
      entry(shown(it), MenuEntryKind.CANCEL).enabled shouldBe false
    }
  }

  "R12 retry and cancel are disabled without a projectId" {
    entry(shown("failed", projectId = null), MenuEntryKind.RETRY).enabled shouldBe false
    entry(shown("running", projectId = null), MenuEntryKind.CANCEL).enabled shouldBe false
  }

  "R12 view is enabled only with a webUrl" {
    entry(shown(webUrl = "https://x"), MenuEntryKind.VIEW).enabled shouldBe true
    entry(shown(webUrl = null), MenuEntryKind.VIEW).enabled shouldBe false
  }

  "no pipeline on a branch offers create and refresh only" {
    kinds(resolved(NoPipeline)) shouldBe listOf(MenuEntryKind.CREATE, MenuEntryKind.REFRESH)
  }

  "a tag pipeline has no create entry" {
    kinds(shown(kind = PipelineKind.TAG)) shouldBe
      listOf(MenuEntryKind.VIEW, MenuEntryKind.RETRY, MenuEntryKind.CANCEL, MenuEntryKind.REFRESH)
  }

  "a merge request pipeline still offers create" {
    kinds(shown(kind = PipelineKind.MERGE_REQUEST)).contains(MenuEntryKind.CREATE) shouldBe true
  }

  "unsettled, failed, ambiguous and no-repository states offer refresh only" {
    kinds(null) shouldBe listOf(MenuEntryKind.REFRESH)
    kinds(Resolved(null, null)) shouldBe listOf(MenuEntryKind.REFRESH)
    kinds(Resolved(null, Result.failure(IllegalStateException("x")))) shouldBe listOf(MenuEntryKind.REFRESH)
    kinds(resolved(AmbiguousTags)) shouldBe listOf(MenuEntryKind.REFRESH)
    kinds(NoRepository) shouldBe listOf(MenuEntryKind.REFRESH)
  }

  "refresh is always enabled" {
    entry(NoRepository, MenuEntryKind.REFRESH).enabled shouldBe true
  }

  "R14 artifacts lists finished jobs only, keeping fetch order" {
    val jobs = listOf(
      GitLabJob(1, "a", "success"),
      GitLabJob(2, "b", "running"),
      GitLabJob(3, "c", "failed"),
      GitLabJob(4, "d", "canceled"),
      GitLabJob(5, "e", "manual"),
      GitLabJob(6, "f", null),
    )
    val e = entry(shown(jobs = Result.success(jobs)), MenuEntryKind.ARTIFACTS)
    e.jobs.map { it.id } shouldBe listOf(1L, 3L, 4L)
    e.enabled shouldBe true
  }

  "artifacts is hidden when no job is finished or the jobs fetch failed" {
    val running = Result.success(listOf(GitLabJob(1, "a", "running")))
    kinds(shown(jobs = running)).contains(MenuEntryKind.ARTIFACTS) shouldBe false
    kinds(shown(jobs = Result.failure(IllegalStateException("x")))).contains(MenuEntryKind.ARTIFACTS) shouldBe false
  }

  "artifacts sits between create and refresh" {
    val jobs = Result.success(listOf(GitLabJob(1, "a", "success")))
    kinds(shown(jobs = jobs)).takeLast(3) shouldBe
      listOf(MenuEntryKind.CREATE, MenuEntryKind.ARTIFACTS, MenuEntryKind.REFRESH)
  }

  "a TAG or DETACHED head never offers create" {
    fun kindsFor(input: CurrentBranchSectionInput, head: HeadKind) =
      PipelineMenuModel.build(CurrentBranchSnapshot(1, "/g", "/w", input, false, head)).map { it.kind }
    kindsFor(resolved(NoPipeline), HeadKind.TAG) shouldBe listOf(MenuEntryKind.REFRESH)
    kindsFor(resolved(NoPipeline), HeadKind.DETACHED) shouldBe listOf(MenuEntryKind.REFRESH)
    kindsFor(shown(kind = PipelineKind.BRANCH), HeadKind.TAG).contains(MenuEntryKind.CREATE) shouldBe false
    kindsFor(resolved(NoPipeline), HeadKind.UNKNOWN) shouldBe listOf(MenuEntryKind.CREATE, MenuEntryKind.REFRESH)
  }
})
