package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.DiscussionsReadResult

/**
 * The sections among an MR's freshly built [children] whose discussions are prefetched for the
 * comment badges: those carrying an [MrCommentBadges]. One build has at most one section, so this
 * is at most one load per build of the MR's children.
 */
internal fun badgePrefetchTargets(children: List<SidebarNode>): List<DiscussionsSectionNode> =
  children.filterIsInstance<DiscussionsSectionNode>().filter { it.commentBadges != null }

/**
 * The [MergeRequestNode] under [roots] whose loaded children hold the section carrying [badges]
 * (matched by identity, so one MR shown in two places resolves to the right node), or null when
 * no node in the tree holds it any more. Depth-first over [SidebarNode.children], stopping at each
 * MR: reading an unloaded node's children yields its "Loading…" placeholder, never a fetch.
 * UI thread only, like the tree it reads.
 */
internal fun mrNodeOwning(roots: List<SidebarNode>, badges: MrCommentBadges): MergeRequestNode? {
  for (node in roots) {
    val found = when (node) {
      is MergeRequestNode -> node.takeIf { mr -> mr.loadedChildren.orEmpty().any { it.holds(badges) } }
      is DiscussionsSectionNode -> null
      else -> mrNodeOwning(node.children, badges)
    }
    if (found != null) return found
  }
  return null
}

/**
 * UI thread, from the discussions loader's settle hook: replaces the index of [section]'s holder
 * with what [result] (null = failed or gate-rejected) says, then hands the MR node that shows it to
 * [refreshSubtree] once so the section and every file repaint. Nothing without a holder; no repaint
 * when the section is no longer in the tree under [roots].
 */
internal fun settleCommentBadges(
  section: DiscussionsSectionNode,
  result: DiscussionsReadResult?,
  roots: List<SidebarNode>,
  refreshSubtree: (MergeRequestNode) -> Unit,
) {
  val badges = section.commentBadges ?: return
  badges.index = commentIndexOf(result, badges.baseSha, badges.headSha)
  mrNodeOwning(roots, badges)?.let(refreshSubtree)
}

private fun SidebarNode.holds(badges: MrCommentBadges): Boolean =
  this is DiscussionsSectionNode && commentBadges === badges

/**
 * Whether a queued cache-hit apply may build an MR node's children (UI thread, inside the queued block). A LIST ↔ TREE
 * toggle can queue two applies for the same current-branch node, so only the first fills it ([alreadyApplied] skips
 * the second, and with it a second discussions prefetch); a full refresh that replaced the tree since the apply was
 * queued ([generationCurrent] false) leaves the detached node alone, as the fetch path does.
 */
internal fun shouldApplyCachedMrChildren(alreadyApplied: Boolean, generationCurrent: Boolean): Boolean =
  !alreadyApplied && generationCurrent
