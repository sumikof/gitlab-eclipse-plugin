package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.model.GitLabMergeRequest
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

private fun section(): DiscussionsSectionNode =
  DiscussionsSectionNode(
    sourceInstanceUrl = "https://gitlab.example.com",
    sourceAuthFingerprint = "fp-1",
    projectId = 42L,
    mrIid = 7L,
    mrGid = "gid://gitlab/MergeRequest/99",
    mrSha = "abc123",
    namespaceWithPath = "group/sub/proj",
  )

/** An MR node with the given loaded children; `null` leaves it unloaded (placeholder children). */
private fun mrNode(children: List<SidebarNode>?): MergeRequestNode =
  MergeRequestNode(
    GitLabMergeRequest(1, 1, "m1", 1, "https://gitlab.example.com/g/p/-/merge_requests/1", "opened"),
  ).apply { loadedChildren = children }

class CollectDiscussionSectionNodeIdsTest : DescribeSpec({
  describe("collectDiscussionSectionNodeIds") {
    it("finds sections nested under merge-request nodes at depth") {
      val assigned = section()
      val currentBranch = section()
      val tree = listOf(
        QueryRootNode(
          "Merge requests assigned to me",
          listOf(ProjectGroupNode("group/proj", listOf(mrNode(listOf(OverviewNode("https://x"), assigned))))),
        ),
        CurrentBranchSectionNode(listOf(mrNode(listOf(currentBranch)))),
      )

      collectDiscussionSectionNodeIds(tree) shouldBe setOf(assigned.nodeId, currentBranch.nodeId)
    }

    it("returns an empty set for a tree with no sections") {
      val tree = listOf(
        QueryRootNode("Issues assigned to me", listOf(MessageNode("No issues"))),
        CurrentBranchSectionNode(listOf(mrNode(listOf(OverviewNode("https://x"))))),
      )

      collectDiscussionSectionNodeIds(tree) shouldBe emptySet<Long>()
    }

    it("deduplicates a section reachable more than once") {
      val shared = section()
      val tree = listOf(mrNode(listOf(shared)), mrNode(listOf(shared)))

      collectDiscussionSectionNodeIds(tree) shouldBe setOf(shared.nodeId)
    }

    it("handles nodes whose children are the unloaded placeholder without triggering anything") {
      val unloadedMr = mrNode(null) // children yields the stable "Loading…" placeholder
      val unloadedSection = section() // loadedChildren null: its own children are the placeholder
      val tree = listOf(unloadedMr, QueryRootNode("root", listOf(unloadedSection)))

      collectDiscussionSectionNodeIds(tree) shouldBe setOf(unloadedSection.nodeId)
    }
  }
})
