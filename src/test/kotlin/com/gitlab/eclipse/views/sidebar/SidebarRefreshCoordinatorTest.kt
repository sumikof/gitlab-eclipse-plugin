package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.api.model.GitLabPipeline
import com.gitlab.eclipse.ci.PipelineKind
import com.gitlab.eclipse.mergerequests.CurrentBranchInfo
import com.gitlab.eclipse.views.sidebar.queries.CustomQueryDefaults
import com.gitlab.eclipse.views.sidebar.queries.QueryConfig
import com.gitlab.eclipse.views.sidebar.queries.QueryOutcome
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs

private const val LOADING = "Loading…"
private const val ISSUES_ID = "default-issues-assigned"
private const val MRS_ID = "default-mrs-assigned"

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

  // The two former "assigned to me" roots, built from the defaults by id.
  fun assignedSlots(generation: Long) =
    RefreshSlots(generation).apply {
      val assigned = CustomQueryDefaults.queries.filter { it.id == ISSUES_ID || it.id == MRS_ID }
      queries = QueriesSlot(QueryConfig.Custom(assigned))
    }
  fun settleAssigned(slots: RefreshSlots, issues: List<GitLabIssue>, mrs: List<GitLabMergeRequest>) {
    slots.queries.outcomes[ISSUES_ID] = Slot.Settled(QueryOutcome.Issues(issues, truncated = false))
    slots.queries.outcomes[MRS_ID] = Slot.Settled(QueryOutcome.MergeRequests(mrs, truncated = false))
  }
  fun sectionOf(nodes: List<SidebarNode>): CurrentBranchSectionNode =
    nodes.last() as CurrentBranchSectionNode
  fun mrNodeOf(section: SidebarNode): MergeRequestNode =
    section.children.filterIsInstance<MergeRequestNode>().single()
  fun loadingOnly(node: SidebarNode) {
    node.children shouldHaveSize 1
    (node.children[0] as MessageNode).label shouldBe LOADING
  }
  fun snapshot() =
    PipelineShown(
      PipelineSnapshot(
        GitLabPipeline(id = 10, status = "success"),
        PipelineKind.BRANCH,
        Result.success(emptyList()),
        Result.success(emptyList()),
        "https://gitlab.example.com",
        "fp0",
      ),
    )

  "fresh slots render one Loading root per default query, then a Loading current-branch section" {
    val out = coordinator().compose(RefreshSlots(1L), SidebarViewMode.LIST)
    out shouldHaveSize CustomQueryDefaults.queries.size + 1
    out.dropLast(1).map { (it as QueryRootNode).queryId } shouldBe CustomQueryDefaults.queries.map { it.id }
    out.dropLast(1).forEach { loadingOnly(it) }
    loadingOnly(sectionOf(out))
  }

  "queries settled with current-branch pending renders real roots and a Loading section" {
    val slots = assignedSlots(1L)
    settleAssigned(slots, listOf(issue(1)), listOf(mr(2)))
    val out = coordinator().compose(slots, SidebarViewMode.LIST)
    out shouldHaveSize 3
    out[0].label shouldBe "Issues assigned to me"
    (out[0].children.single() is IssueNode) shouldBe true
    out[1].label shouldBe "Merge requests assigned to me"
    (out[1].children.single() is MergeRequestNode) shouldBe true
    loadingOnly(sectionOf(out))
  }

  "cross-unit identity: queries settling later keeps the current-branch MergeRequestNode instance" {
    val c = coordinator()
    val slots = assignedSlots(5L)
    val mrResult = Result.success(CurrentBranchInfo(mr(2), emptyList()))
    slots.currentBranch = Slot.Settled(Resolved(mr = mrResult, pipeline = null))
    val first = mrNodeOf(sectionOf(c.compose(slots, SidebarViewMode.LIST)))
    settleAssigned(slots, emptyList(), listOf(mr(3)))
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
    val slots = assignedSlots(1L)
    settleAssigned(slots, listOf(issue(1)), listOf(mr(2)))
    slots.currentBranch =
      Slot.Settled(
        Resolved(
          mr = Result.success(CurrentBranchInfo(mr(2), emptyList())),
          pipeline = Result.success(snapshot()),
        ),
      )
    val settled = c.compose(slots, SidebarViewMode.LIST)
    sectionOf(settled).children.filterIsInstance<MergeRequestNode>() shouldHaveSize 1

    val out = c.compose(assignedSlots(2L), SidebarViewMode.LIST)
    out shouldHaveSize 3
    loadingOnly(out[0])
    loadingOnly(out[1])
    loadingOnly(sectionOf(out))
    out[0] shouldNotBeSameInstanceAs settled[0]
    sectionOf(out) shouldNotBeSameInstanceAs sectionOf(settled)
  }

  "unchanged query outcomes reuse the same root node instances" {
    val c = coordinator()
    val slots = assignedSlots(3L)
    settleAssigned(slots, listOf(issue(1)), listOf(mr(2)))
    val out1 = c.compose(slots, SidebarViewMode.LIST)
    slots.currentBranch = Slot.Settled(NoRepository)
    val out2 = c.compose(slots, SidebarViewMode.LIST)
    out2[0] shouldBeSameInstanceAs out1[0]
    out2[1] shouldBeSameInstanceAs out1[1]
  }

  "still-pending query roots keep identity while current-branch settles" {
    val c = coordinator()
    val slots = assignedSlots(4L)
    val out1 = c.compose(slots, SidebarViewMode.LIST)
    slots.currentBranch =
      Slot.Settled(Resolved(mr = Result.success(CurrentBranchInfo(mr(2), emptyList())), pipeline = null))
    val out2 = c.compose(slots, SidebarViewMode.LIST)
    out2[0] shouldBeSameInstanceAs out1[0]
    out2[1] shouldBeSameInstanceAs out1[1]
  }
})
