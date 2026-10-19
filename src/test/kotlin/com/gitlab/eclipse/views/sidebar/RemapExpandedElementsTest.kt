package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.api.model.GitLabPipeline
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

/**
 * Pure remapping of a captured expanded-elements list onto a freshly composed input (Task 9,
 * R9): rebuilt top-level nodes are matched by stable key, identity-preserved inner nodes pass
 * through unchanged, unmatched stale elements pass through best-effort.
 */
class RemapExpandedElementsTest : StringSpec({
  fun mrNode() =
    MergeRequestNode(GitLabMergeRequest(1, 1, "m1", 1, "mu1", "opened"))
  fun root(label: String) = QueryRootNode(label, emptyList())
  fun section() = CurrentBranchSectionNode(emptyList())

  "query root is remapped to the new instance with the same label" {
    val oldRoot = root("Issues assigned to me")
    val newRoot = root("Issues assigned to me")
    val newInput = listOf<SidebarNode>(newRoot, root("Merge requests assigned to me"))

    val remapped = remapExpandedElements(listOf(oldRoot), newInput)

    remapped.single() shouldBeSameInstanceAs newRoot
  }

  "current-branch section is remapped by type" {
    val oldSection = section()
    val newSection = section()
    val newInput = listOf<SidebarNode>(root("Issues assigned to me"), newSection)

    val remapped = remapExpandedElements(listOf(oldSection), newInput)

    remapped.single() shouldBeSameInstanceAs newSection
  }

  "inner nodes pass through unchanged (coordinator preserves their identity)" {
    val mr = mrNode()
    val newInput = listOf<SidebarNode>(root("Merge requests assigned to me"), section())

    val remapped = remapExpandedElements(listOf(section(), mr), newInput)

    remapped[1] shouldBeSameInstanceAs mr
  }

  "unmatched top-level elements pass through best-effort" {
    val stale = root("Gone root")
    val newInput = listOf<SidebarNode>(root("Issues assigned to me"))

    val remapped = remapExpandedElements(listOf(stale), newInput)

    remapped.single() shouldBeSameInstanceAs stale
  }

  "order is preserved" {
    val issuesOld = root("Issues assigned to me")
    val mr = mrNode()
    val sectionOld = section()
    val issuesNew = root("Issues assigned to me")
    val sectionNew = section()
    val newInput = listOf<SidebarNode>(issuesNew, sectionNew)

    val remapped = remapExpandedElements(listOf(issuesOld, mr, sectionOld), newInput)

    remapped shouldBe listOf(issuesNew, mr, sectionNew)
  }

  // CI-04: a soft refresh rebuilds only the current-branch section's nodes, so its MR, pipeline and
  // stage nodes are remapped by stable key — and only those that were inside the old section.
  fun cbMr(iid: Long = 1, projectId: Long = 1, sha: String = "s1") =
    MergeRequestNode(GitLabMergeRequest(iid, iid, "m$iid", projectId, "mu$iid", "opened", sha = sha))

  "a current-branch MR node is remapped to the new section's MR with the same project and iid" {
    val oldMr = cbMr(sha = "s1")
    val newMr = cbMr(sha = "s2")
    val oldInput = listOf<SidebarNode>(CurrentBranchSectionNode(listOf(oldMr)))
    val newSection = CurrentBranchSectionNode(listOf(cbMr(iid = 2), newMr))

    val remapped = remapExpandedElements(listOf(oldInput[0], oldMr), listOf(newSection), oldInput)

    remapped shouldBe listOf(newSection, newMr)
  }

  "an expanded assigned MR is not remapped onto the current-branch copy of the same MR" {
    val assignedMr = cbMr()
    val oldInput = listOf<SidebarNode>(QueryRootNode("Merge requests assigned to me", listOf(assignedMr)), section())
    val newInput = listOf<SidebarNode>(root("Merge requests assigned to me"), CurrentBranchSectionNode(listOf(cbMr())))

    remapExpandedElements(listOf(assignedMr), newInput, oldInput).single() shouldBeSameInstanceAs assignedMr
  }

  fun pipelineNode(id: Long, status: String, vararg stages: StageNode) =
    PipelineNode(
      GitLabPipeline(id = id, status = status),
      stages.toList(),
      canRetry = false,
      canCancel = false,
      sourceInstanceUrl = "https://gitlab.example.com",
      sourceAuthFingerprint = "fp",
    )

  "a current-branch pipeline node is remapped by pipeline id" {
    val oldPipeline = pipelineNode(5, "running")
    val newPipeline = pipelineNode(5, "success")
    val oldInput = listOf<SidebarNode>(CurrentBranchSectionNode(listOf(cbMr(), oldPipeline)))
    val newSection = CurrentBranchSectionNode(listOf(pipelineNode(4, "success"), newPipeline))

    remapExpandedElements(listOf(oldPipeline), listOf(newSection), oldInput).single() shouldBeSameInstanceAs newPipeline
  }

  "a stage node is remapped by its pipeline's id and its label" {
    val oldStage = StageNode("test", emptyList())
    val oldPipeline = pipelineNode(5, "running", StageNode("build", emptyList()), oldStage)
    val newStage = StageNode("test", emptyList())
    val newPipeline = pipelineNode(5, "success", StageNode("build", emptyList()), newStage)
    val oldInput = listOf<SidebarNode>(CurrentBranchSectionNode(listOf(oldPipeline)))
    val otherPipeline = pipelineNode(4, "success", StageNode("test", emptyList()))
    val newSection = CurrentBranchSectionNode(listOf(otherPipeline, newPipeline))

    remapExpandedElements(listOf(oldPipeline, oldStage), listOf(newSection), oldInput) shouldBe listOf(newPipeline, newStage)
  }

  "a stage whose pipeline is gone passes through" {
    val oldStage = StageNode("test", emptyList())
    val oldInput = listOf<SidebarNode>(CurrentBranchSectionNode(listOf(pipelineNode(5, "running", oldStage))))
    val newSection = CurrentBranchSectionNode(listOf(pipelineNode(6, "running", StageNode("test", emptyList()))))

    remapExpandedElements(listOf(oldStage), listOf(newSection), oldInput).single() shouldBeSameInstanceAs oldStage
  }
})
