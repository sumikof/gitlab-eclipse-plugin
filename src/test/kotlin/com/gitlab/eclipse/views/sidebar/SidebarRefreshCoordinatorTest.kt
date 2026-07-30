package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.api.model.GitLabPipeline
import com.gitlab.eclipse.mergerequests.CurrentBranchInfo
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs

private const val LOADING = "Loading…"
private const val ISSUES_ROOT = "Issues assigned to me"
private const val MRS_ROOT = "Merge requests assigned to me"

class SidebarRefreshCoordinatorTest : StringSpec({
  fun issue(iid: Long) =
    GitLabIssue(iid, iid, "t$iid", "u$iid", "opened", GitLabIssue.Reference("g/p#$iid"))
  fun mr(iid: Long) =
    GitLabMergeRequest(
      iid,
      iid,
      "m$iid",
      1,
      "https://gitlab.example.com/g/p/-/merge_requests/$iid",
      "opened",
      references = GitLabMergeRequest.Reference("g/p!$iid"),
    )
  fun coordinator() = SidebarRefreshCoordinator(SidebarViewModel())
  fun sectionOf(nodes: List<SidebarNode>): CurrentBranchSectionNode =
    nodes[2] as CurrentBranchSectionNode
  fun mrNodeOf(section: SidebarNode): MergeRequestNode =
    section.children.filterIsInstance<MergeRequestNode>().single()
  fun loadingOnly(node: SidebarNode) {
    node.children shouldHaveSize 1
    (node.children[0] as MessageNode).label shouldBe LOADING
  }
  fun snapshot() =
    PipelineSnapshot(GitLabPipeline(id = 10, status = "success"), Result.success(emptyList()))

  "all-pending slots render two Loading roots and a Loading current-branch section" {
    val out = coordinator().compose(RefreshSlots(1L), SidebarViewMode.LIST)
    out shouldHaveSize 3
    out[0].label shouldBe ISSUES_ROOT
    loadingOnly(out[0])
    out[1].label shouldBe MRS_ROOT
    loadingOnly(out[1])
    loadingOnly(sectionOf(out))
  }

  "assigned settled with current-branch pending renders real roots and a Loading section" {
    val slots = RefreshSlots(1L)
    slots.assigned =
      Slot.Settled(Result.success(listOf(issue(1))) to Result.success(listOf(mr(2))))
    val out = coordinator().compose(slots, SidebarViewMode.LIST)
    out shouldHaveSize 3
    out[0].label shouldBe ISSUES_ROOT
    (out[0].children.single() is IssueNode) shouldBe true
    out[1].label shouldBe MRS_ROOT
    (out[1].children.single() is MergeRequestNode) shouldBe true
    loadingOnly(sectionOf(out))
  }

  "cross-unit identity: assigned settling later keeps the current-branch MergeRequestNode instance" {
    val c = coordinator()
    val slots = RefreshSlots(5L)
    val mrResult = Result.success(CurrentBranchInfo(mr(2), emptyList()))
    slots.currentBranch = Slot.Settled(Resolved(mr = mrResult, pipeline = null))
    val first = mrNodeOf(sectionOf(c.compose(slots, SidebarViewMode.LIST)))
    slots.assigned =
      Slot.Settled(Result.success(emptyList<GitLabIssue>()) to Result.success(listOf(mr(3))))
    val second = mrNodeOf(sectionOf(c.compose(slots, SidebarViewMode.LIST)))
    second shouldBeSameInstanceAs first
  }

  "within-section identity: pipeline settling keeps the MergeRequestNode and adds a PipelineNode" {
    val c = coordinator()
    val slots = RefreshSlots(7L)
    val mrResult = Result.success(CurrentBranchInfo(mr(2), emptyList()))
    slots.currentBranch = Slot.Settled(Resolved(mr = mrResult, pipeline = null))
    val first = mrNodeOf(sectionOf(c.compose(slots, SidebarViewMode.LIST)))
    slots.currentBranch =
      Slot.Settled(Resolved(mr = mrResult, pipeline = Result.success(snapshot())))
    val out = c.compose(slots, SidebarViewMode.LIST)
    mrNodeOf(sectionOf(out)) shouldBeSameInstanceAs first
    sectionOf(out).children.filterIsInstance<PipelineNode>() shouldHaveSize 1
  }

  "changed mr result rebuilds the MergeRequestNode as a new instance" {
    val c = coordinator()
    val slots = RefreshSlots(9L)
    slots.currentBranch =
      Slot.Settled(Resolved(mr = Result.success(CurrentBranchInfo(mr(2), emptyList())), pipeline = null))
    val first = mrNodeOf(sectionOf(c.compose(slots, SidebarViewMode.LIST)))
    slots.currentBranch =
      Slot.Settled(Resolved(mr = Result.success(CurrentBranchInfo(mr(2), emptyList())), pipeline = null))
    val second = mrNodeOf(sectionOf(c.compose(slots, SidebarViewMode.LIST)))
    second shouldNotBeSameInstanceAs first
  }

  "new generation resets to all-Loading with no prior-generation nodes leaking" {
    val c = coordinator()
    val slots = RefreshSlots(1L)
    slots.assigned =
      Slot.Settled(Result.success(listOf(issue(1))) to Result.success(listOf(mr(2))))
    slots.currentBranch =
      Slot.Settled(
        Resolved(
          mr = Result.success(CurrentBranchInfo(mr(2), emptyList())),
          pipeline = Result.success(snapshot()),
        ),
      )
    val settled = c.compose(slots, SidebarViewMode.LIST)
    sectionOf(settled).children.filterIsInstance<MergeRequestNode>() shouldHaveSize 1

    val out = c.compose(RefreshSlots(2L), SidebarViewMode.LIST)
    out shouldHaveSize 3
    loadingOnly(out[0])
    loadingOnly(out[1])
    loadingOnly(sectionOf(out))
    sectionOf(out) shouldNotBeSameInstanceAs sectionOf(settled)
  }

  "unchanged assigned slot reuses the same root node instances" {
    val c = coordinator()
    val slots = RefreshSlots(3L)
    slots.assigned =
      Slot.Settled(Result.success(listOf(issue(1))) to Result.success(listOf(mr(2))))
    val out1 = c.compose(slots, SidebarViewMode.LIST)
    slots.currentBranch = Slot.Settled(NoRepository)
    val out2 = c.compose(slots, SidebarViewMode.LIST)
    out2[0] shouldBeSameInstanceAs out1[0]
    out2[1] shouldBeSameInstanceAs out1[1]
  }

  "still-pending assigned roots keep identity while current-branch settles" {
    val c = coordinator()
    val slots = RefreshSlots(4L)
    val out1 = c.compose(slots, SidebarViewMode.LIST)
    slots.currentBranch =
      Slot.Settled(Resolved(mr = Result.success(CurrentBranchInfo(mr(2), emptyList())), pipeline = null))
    val out2 = c.compose(slots, SidebarViewMode.LIST)
    out2[0] shouldBeSameInstanceAs out1[0]
    out2[1] shouldBeSameInstanceAs out1[1]
  }
})
