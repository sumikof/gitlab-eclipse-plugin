package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.DiscussionsReadResult
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

private fun mergeRequest() = MergeRequestNode(
  GitLabMergeRequest(id = 1, iid = 1, title = "t", projectId = 1, webUrl = "https://h/g/p/-/merge_requests/1", state = "opened"),
)

private fun section(badges: MrCommentBadges?) = DiscussionsSectionNode(
  sourceInstanceUrl = "https://h",
  sourceAuthFingerprint = "fp",
  projectId = 1L,
  mrIid = 1L,
  mrGid = "gid://gitlab/MergeRequest/1",
  mrSha = null,
  namespaceWithPath = "g/p",
  commentBadges = badges,
)

/** An MR node whose children were built with [badges]: Overview, the section, one changed file. */
private fun loadedMr(badges: MrCommentBadges?): MergeRequestNode = mergeRequest().also { mr ->
  mr.loadedChildren = listOf(
    OverviewNode(mr.url),
    section(badges),
    ChangedFileNode("a.kt", "a.kt", ChangeType.MODIFIED, "head", commentBadges = badges),
  )
}

private fun badges() = MrCommentBadges("base", "head")

class CommentBadgeBindingTest : StringSpec({
  "prefetch targets are the sections that carry a holder, and nothing else" {
    val withBadges = section(badges())
    val children = listOf(OverviewNode("u"), withBadges, section(null), MessageNode("m"))
    badgePrefetchTargets(children) shouldContainExactly listOf(withBadges)
    badgePrefetchTargets(listOf(OverviewNode("u"), section(null))).shouldBeEmpty()
  }

  "finds the MR under a query root, under a project group and under the current-branch section" {
    val b1 = badges()
    val b2 = badges()
    val b3 = badges()
    val underRoot = loadedMr(b1)
    val underGroup = loadedMr(b2)
    val underCurrentBranch = loadedMr(b3)
    val roots = listOf(
      QueryRootNode("q1", "Assigned", listOf(underRoot)),
      QueryRootNode("q2", "Review", listOf(ProjectGroupNode("g/p", listOf(underGroup)))),
      CurrentBranchSectionNode(listOf(underCurrentBranch)),
    )
    mrNodeOwning(roots, b1) shouldBeSameInstanceAs underRoot
    mrNodeOwning(roots, b2) shouldBeSameInstanceAs underGroup
    mrNodeOwning(roots, b3) shouldBeSameInstanceAs underCurrentBranch
  }

  "picks the MR whose section holds the same holder when one MR shows in two places" {
    val first = badges()
    val second = badges()
    val assigned = loadedMr(first)
    val currentBranch = loadedMr(second)
    val roots = listOf(QueryRootNode("q", "Assigned", listOf(assigned)), CurrentBranchSectionNode(listOf(currentBranch)))
    mrNodeOwning(roots, second) shouldBeSameInstanceAs currentBranch
    mrNodeOwning(roots, first) shouldBeSameInstanceAs assigned
  }

  "an orphaned holder (no MR in the tree holds its section) finds nothing" {
    val roots = listOf(QueryRootNode("q", "Assigned", listOf(loadedMr(badges()), mergeRequest())))
    mrNodeOwning(roots, badges()) shouldBe null
    mrNodeOwning(emptyList(), badges()) shouldBe null
  }

  "walking an unloaded MR reads only its placeholder and loads nothing" {
    val unloaded = mergeRequest()
    mrNodeOwning(listOf(QueryRootNode("q", "Assigned", listOf(unloaded))), badges()) shouldBe null
    unloaded.loadedChildren shouldBe null
  }

  "settle replaces the index and repaints the owning MR exactly once" {
    val holder = badges()
    val mr = loadedMr(holder)
    val sectionNode = mr.loadedChildren!!.filterIsInstance<DiscussionsSectionNode>().single()
    val repainted = mutableListOf<MergeRequestNode>()

    settleCommentBadges(sectionNode, DiscussionsReadResult(true, emptyList(), null), listOf(mr)) { repainted += it }

    holder.index shouldBe CommentIndex.Complete(CommentedSides(emptySet(), emptySet()))
    repainted shouldContainExactly listOf(mr)
  }

  "settle with no result makes the index unavailable" {
    val holder = badges().also { it.index = CommentIndex.Complete(CommentedSides(emptySet(), setOf("a.kt"))) }
    val mr = loadedMr(holder)
    val sectionNode = mr.loadedChildren!!.filterIsInstance<DiscussionsSectionNode>().single()

    settleCommentBadges(sectionNode, null, listOf(mr)) {}

    holder.index shouldBe CommentIndex.Unavailable
  }

  "settle on a section without a holder does nothing" {
    val mr = loadedMr(null)
    val sectionNode = mr.loadedChildren!!.filterIsInstance<DiscussionsSectionNode>().single()
    val repainted = mutableListOf<MergeRequestNode>()

    settleCommentBadges(sectionNode, DiscussionsReadResult(true, emptyList(), null), listOf(mr)) { repainted += it }

    repainted.shouldBeEmpty()
  }

  "settle on an orphaned section updates its own holder but repaints nothing" {
    val holder = badges()
    val orphan = section(holder)
    val repainted = mutableListOf<MergeRequestNode>()

    settleCommentBadges(orphan, DiscussionsReadResult(true, emptyList(), null), listOf(loadedMr(badges()))) {
      repainted += it
    }

    holder.index shouldBe CommentIndex.Complete(CommentedSides(emptySet(), emptySet()))
    repainted.shouldBeEmpty()
  }
})
