package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.model.GitLabMergeRequest
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
})
