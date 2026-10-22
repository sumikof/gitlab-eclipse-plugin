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
  fun root(id: String, label: String = id) = QueryRootNode(id, label, emptyList())
  fun section() = CurrentBranchSectionNode(emptyList())

  "query root is remapped to the new instance with the same query id" {
    val oldRoot = root("issues")
    val newRoot = root("issues")
    val newInput = listOf<SidebarNode>(newRoot, root("mrs"))

    val remapped = remapExpandedElements(listOf(oldRoot), newInput)

    remapped.single() shouldBeSameInstanceAs newRoot
  }

  "current-branch section is remapped by type" {
    val oldSection = section()
    val newSection = section()
    val newInput = listOf<SidebarNode>(root("issues"), newSection)

    val remapped = remapExpandedElements(listOf(oldSection), newInput)

    remapped.single() shouldBeSameInstanceAs newSection
  }

  "inner nodes pass through unchanged (coordinator preserves their identity)" {
    val mr = mrNode()
    val newInput = listOf<SidebarNode>(root("mrs"), section())

    val remapped = remapExpandedElements(listOf(section(), mr), newInput)

    remapped[1] shouldBeSameInstanceAs mr
  }

  "unmatched top-level elements pass through best-effort" {
    val stale = root("gone")
    val newInput = listOf<SidebarNode>(root("issues"))

    val remapped = remapExpandedElements(listOf(stale), newInput)

    remapped.single() shouldBeSameInstanceAs stale
  }

  "order is preserved" {
    val issuesOld = root("issues")
    val mr = mrNode()
    val sectionOld = section()
    val issuesNew = root("issues")
    val sectionNew = section()
    val newInput = listOf<SidebarNode>(issuesNew, sectionNew)

    val remapped = remapExpandedElements(listOf(issuesOld, mr, sectionOld), newInput)

    remapped shouldBe listOf(issuesNew, mr, sectionNew)
  }

  "two roots with the same label but different ids each map to their own id" {
    val oldA = root("a", "Same")
    val oldB = root("b", "Same")
    val newA = root("a", "Same")
    val newB = root("b", "Same")

    remapExpandedElements(listOf(oldB, oldA), listOf<SidebarNode>(newA, newB)) shouldBe listOf(newB, newA)
  }

  "a renamed root keeps its expansion" {
    val old = root("a", "Old name")
    val renamed = root("a", "New name")

    remapExpandedElements(listOf(old), listOf<SidebarNode>(root("b", "Old name"), renamed))
      .single() shouldBeSameInstanceAs renamed
  }

  "a root whose id was removed passes through and is not found" {
    val removed = root("a", "Same")

    remapExpandedElements(listOf(removed), listOf<SidebarNode>(root("b", "Same")))
      .single() shouldBeSameInstanceAs removed
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
    val oldInput = listOf<SidebarNode>(QueryRootNode("mrs", "MRs", listOf(assignedMr)), section())
    val newInput = listOf<SidebarNode>(root("mrs"), CurrentBranchSectionNode(listOf(cbMr())))

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

  "a stage node is remapped by its pipeline's id and its name" {
    val oldStage = StageNode("test", "Running", emptyList())
    val oldPipeline = pipelineNode(5, "running", StageNode("build", "Passed", emptyList()), oldStage)
    val newStage = StageNode("test", "Running", emptyList())
    val newPipeline = pipelineNode(5, "success", StageNode("build", "Passed", emptyList()), newStage)
    val oldInput = listOf<SidebarNode>(CurrentBranchSectionNode(listOf(oldPipeline)))
    val otherPipeline = pipelineNode(4, "success", StageNode("test", "Running", emptyList()))
    val newSection = CurrentBranchSectionNode(listOf(otherPipeline, newPipeline))

    remapExpandedElements(listOf(oldPipeline, oldStage), listOf(newSection), oldInput) shouldBe listOf(newPipeline, newStage)
  }

  "a stage whose pipeline is gone passes through" {
    val oldStage = StageNode("test", "Running", emptyList())
    val oldInput = listOf<SidebarNode>(CurrentBranchSectionNode(listOf(pipelineNode(5, "running", oldStage))))
    val newStage = StageNode("test", "Running", emptyList())
    val newSection = CurrentBranchSectionNode(listOf(pipelineNode(6, "running", newStage)))

    remapExpandedElements(listOf(oldStage), listOf(newSection), oldInput).single() shouldBeSameInstanceAs oldStage
  }

  "CI-05: a stage whose status changed (so its label changed) is still remapped by name" {
    val oldStage = StageNode("test", "Running", emptyList())
    val oldInput = listOf<SidebarNode>(CurrentBranchSectionNode(listOf(pipelineNode(5, "running", oldStage))))
    val newStage = StageNode("test", "Failed", emptyList())
    val newSection = CurrentBranchSectionNode(
      listOf(pipelineNode(5, "failed", StageNode("build", "Running", emptyList()), newStage)),
    )

    remapExpandedElements(listOf(oldStage), listOf(newSection), oldInput).single() shouldBeSameInstanceAs newStage
  }

  // CI-06 (Task 5, R-T5-1 / M11): the 30 s soft rebuild re-creates the root pipeline whenever a running
  // downstream moves its `updated_at`, so the "Downstream pipelines" group (keyed by its parent pipeline
  // id), each downstream pipeline (keyed by its own id, under its group) and everything nested in them
  // must keep their expansion. The rebuild runs [transferDownstreamState] before the remap, as applyCompose does.
  fun ds(id: Long, depth: Int = 1, ancestors: Set<Long> = setOf(5L)) =
    DownstreamPipelineNode("trigger$id", downstream(id), depth, ancestors, "https://gitlab.example.com", "fp", 42L)

  fun DownstreamPipelineNode.loaded(vararg children: SidebarNode) = apply {
    loadState = DownstreamLoadState.LOADED
    loadedChildren = children.toList()
    shownPayload = DownstreamPayload(Result.success(emptyList()), Result.success(emptyList()))
  }

  fun pipelineWith(id: Long, status: String, vararg children: SidebarNode) =
    PipelineNode(
      GitLabPipeline(id = id, status = status),
      children.toList(),
      canRetry = false,
      canCancel = false,
      sourceInstanceUrl = "https://gitlab.example.com",
      sourceAuthFingerprint = "fp",
    )

  "CI-06: an expanded downstream group, downstream node and inner stage all survive a rebuild with the same ids" {
    val innerStage = StageNode("deploy", "Running", emptyList())
    val oldDs = ds(205).loaded(innerStage)
    val oldGroup = DownstreamPipelinesNode(5L, listOf(oldDs))
    val oldPipeline = pipelineWith(5, "running", StageNode("build", "Passed", emptyList()), oldGroup)
    val oldInput = listOf<SidebarNode>(CurrentBranchSectionNode(listOf(oldPipeline)))
    val newDs = ds(205)
    val newGroup = DownstreamPipelinesNode(5L, listOf(ds(204), newDs))
    val newPipeline = pipelineWith(5, "running", StageNode("build", "Passed", emptyList()), newGroup)
    val newInput = listOf<SidebarNode>(CurrentBranchSectionNode(listOf(newPipeline)))

    transferDownstreamState(oldInput, newInput)
    val remapped = remapExpandedElements(listOf(oldPipeline, oldGroup, oldDs, innerStage), newInput, oldInput)

    remapped shouldBe listOf(newPipeline, newGroup, newDs, innerStage)
    remapped[3] shouldBeSameInstanceAs newDs.children.single()
  }

  "CI-06: nested downstream groups, nodes and their stages remap through the transferred parent" {
    val nestedStage = StageNode("test", "Failed", emptyList())
    val nested = ds(300, depth = 2, ancestors = setOf(5L, 205L)).loaded(nestedStage)
    val nestedGroup = DownstreamPipelinesNode(205L, listOf(nested))
    val oldDs = ds(205).loaded(StageNode("deploy", "Running", emptyList()), nestedGroup)
    val oldGroup = DownstreamPipelinesNode(5L, listOf(oldDs))
    val oldInput = listOf<SidebarNode>(CurrentBranchSectionNode(listOf(pipelineWith(5, "running", oldGroup))))
    val newDs = ds(205)
    val newInput = listOf<SidebarNode>(
      CurrentBranchSectionNode(listOf(pipelineWith(5, "success", DownstreamPipelinesNode(5L, listOf(newDs))))),
    )

    transferDownstreamState(oldInput, newInput)
    val remapped = remapExpandedElements(listOf(oldGroup, oldDs, nestedGroup, nested, nestedStage), newInput, oldInput)

    remapped[1] shouldBeSameInstanceAs newDs
    remapped.drop(2) shouldBe listOf(nestedGroup, nested, nestedStage)
    newDs.children[1] shouldBeSameInstanceAs nestedGroup
  }

  "CI-06: without a transfer (old node still loading) the group and the node remap; the stale inner stage passes through" {
    val innerStage = StageNode("deploy", "Running", emptyList())
    val oldDs = ds(205).loaded(innerStage).apply { loadState = DownstreamLoadState.LOADING }
    val oldGroup = DownstreamPipelinesNode(5L, listOf(oldDs))
    val oldInput = listOf<SidebarNode>(CurrentBranchSectionNode(listOf(pipelineWith(5, "running", oldGroup))))
    val newDs = ds(205)
    val newGroup = DownstreamPipelinesNode(5L, listOf(newDs))
    val newInput = listOf<SidebarNode>(CurrentBranchSectionNode(listOf(pipelineWith(5, "running", newGroup))))

    transferDownstreamState(oldInput, newInput)

    remapExpandedElements(listOf(oldGroup, oldDs, innerStage), newInput, oldInput) shouldBe listOf(newGroup, newDs, innerStage)
  }

  "CI-06: a downstream stage is remapped under its own downstream, never onto the root's stage of the same name" {
    val oldDsStage = StageNode("build", "Running", emptyList())
    val oldDs = ds(205).loaded(oldDsStage)
    val oldGroup = DownstreamPipelinesNode(5L, listOf(oldDs))
    val oldPipeline = pipelineWith(5, "running", StageNode("build", "Passed", emptyList()), oldGroup)
    val oldInput = listOf<SidebarNode>(CurrentBranchSectionNode(listOf(oldPipeline)))
    val rootBuild = StageNode("build", "Passed", emptyList())
    val newDsStage = StageNode("build", "Passed", emptyList())
    val newDs = ds(205).loaded(newDsStage)
    val newInput = listOf<SidebarNode>(
      CurrentBranchSectionNode(
        listOf(pipelineWith(5, "running", rootBuild, DownstreamPipelinesNode(5L, listOf(newDs)))),
      ),
    )

    remapExpandedElements(listOf(oldDsStage), newInput, oldInput).single() shouldBeSameInstanceAs newDsStage
  }

  "CI-06: a downstream node is matched by pipeline id only within its own group" {
    val oldDs = ds(205)
    val oldInput = listOf<SidebarNode>(
      CurrentBranchSectionNode(listOf(pipelineWith(5, "running", DownstreamPipelinesNode(5L, listOf(oldDs))))),
    )
    val newInput = listOf<SidebarNode>(
      CurrentBranchSectionNode(listOf(pipelineWith(6, "running", DownstreamPipelinesNode(6L, listOf(ds(205)))))),
    )

    remapExpandedElements(listOf(oldDs), newInput, oldInput).single() shouldBeSameInstanceAs oldDs
  }

  "CI-06: the sweep's subtree remap maps the old children of one downstream node onto its new children" {
    val oldStage = StageNode("deploy", "Running", emptyList())
    val nested = ds(300, depth = 2, ancestors = setOf(5L, 205L))
    val oldGroup = DownstreamPipelinesNode(205L, listOf(nested))
    val newStage = StageNode("deploy", "Passed", emptyList())
    val newNested = ds(300, depth = 2, ancestors = setOf(5L, 205L))
    val newGroup = DownstreamPipelinesNode(205L, listOf(newNested))
    val oldChildren = listOf<SidebarNode>(oldStage, oldGroup)
    val newChildren = listOf<SidebarNode>(newStage, newGroup)

    remapInChildren(oldStage, oldChildren, newChildren) shouldBeSameInstanceAs newStage
    remapInChildren(oldGroup, oldChildren, newChildren) shouldBeSameInstanceAs newGroup
    remapInChildren(nested, oldChildren, newChildren) shouldBeSameInstanceAs newNested
    remapInChildren(MessageNode("elsewhere"), oldChildren, newChildren) shouldBe null
  }
})
