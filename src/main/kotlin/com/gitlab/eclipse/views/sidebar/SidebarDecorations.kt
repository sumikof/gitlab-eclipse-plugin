package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.ci.CiStatusIcon
import com.gitlab.eclipse.ci.ciStatusIcon

/** Prefix of the theme color ids the sidebar's change badges are drawn with. */
const val CHANGE_COLOR_PREFIX = "com.gitlab.eclipse.sidebar."

/** Which theme color a change badge uses. */
enum class ChangeColorRole { ADDED, DELETED, RENAMED }

/** Single-letter change marker of a changed file; [colorRole] is null for the default foreground. */
data class ChangeBadge(val letter: Char, val colorRole: ChangeColorRole?)

/** Status icon of a CI node, or null for nodes that carry none. */
fun sidebarStatusIcon(node: SidebarNode): CiStatusIcon? =
  when (node) {
    is PipelineNode -> ciStatusIcon(node.status)
    is DownstreamPipelineNode -> ciStatusIcon(node.pipeline.status)
    is JobNode -> ciStatusIcon(node.job.status, node.job.allowFailure ?: false)
    is BridgeNode -> ciStatusIcon(node.bridge.status, node.bridge.allowFailure ?: false)
    is StageNode -> ciStatusIcon(node.aggregate.first, node.aggregate.second)
    is DownstreamPipelinesNode -> ciStatusIcon(node.aggregate.first, node.aggregate.second)
    else -> null
  }

/** Change badge of a [ChangedFileNode], null for any other node. */
fun changeBadge(node: SidebarNode): ChangeBadge? =
  if (node is ChangedFileNode) {
    when (node.changeType) {
      ChangeType.NEW -> ChangeBadge('A', ChangeColorRole.ADDED)
      ChangeType.DELETED -> ChangeBadge('D', ChangeColorRole.DELETED)
      ChangeType.RENAMED -> ChangeBadge('R', ChangeColorRole.RENAMED)
      ChangeType.MODIFIED -> ChangeBadge('M', null)
    }
  } else {
    null
  }

/**
 * Icon registry name of a changed file with a comment (VS Code's 💬 badge): the editor's unresolved-thread icon, so
 * the sidebar and the ruler use the same picture.
 */
const val COMMENT_BADGE_ICON = "chat_on_obj"

/** Suffix of the Discussions section while its comment badges are still loading. */
internal const val COMMENT_BADGES_LOADING_SUFFIX = " · $LOADING_MESSAGE"

/** Suffix of the Discussions section when the fetch was truncated: a file without a badge may still have a comment. */
internal const val COMMENT_BADGES_INCOMPLETE_SUFFIX = " · comment badges incomplete"

/** Suffix of the Discussions section when the comments could not be fetched: no file's state is known. */
internal const val COMMENT_BADGES_UNAVAILABLE_SUFFIX = " · comment badges unavailable"

/**
 * Image registry name for [node]: a CI node's status icon, [COMMENT_BADGE_ICON] for a changed file known to have a
 * comment, null otherwise. An unknown state draws nothing on the file; the section text says so instead.
 */
fun sidebarImageName(node: SidebarNode): String? =
  when (node) {
    is ChangedFileNode -> COMMENT_BADGE_ICON.takeIf { node.commentState() == FileCommentState.PRESENT }
    else -> sidebarStatusIcon(node)?.iconName
  }

private fun ChangedFileNode.commentState(): FileCommentState? =
  commentBadges?.let { fileCommentState(oldPath, newPath, changeType, it.index) }

/**
 * Text drawn for [node]: a changed file reads `<name> · <letter>` (name = last path segment in the tree), a Discussions
 * section carries how far its comment badges can be trusted, others use the label.
 */
fun sidebarDisplayText(node: SidebarNode): String {
  if (node is DiscussionsSectionNode) return node.label + commentBadgesSuffix(node.commentBadges)
  val badge = changeBadge(node) ?: return node.label
  val name = if ((node as ChangedFileNode).inTree) node.label.substringAfterLast('/') else node.label
  return "$name · ${badge.letter}"
}

private fun commentBadgesSuffix(badges: MrCommentBadges?): String =
  when (badges?.index) {
    null, is CommentIndex.Complete -> ""
    CommentIndex.Pending -> COMMENT_BADGES_LOADING_SUFFIX
    is CommentIndex.Partial -> COMMENT_BADGES_INCOMPLETE_SUFFIX
    CommentIndex.Unavailable -> COMMENT_BADGES_UNAVAILABLE_SUFFIX
  }

/** Theme color id for [role]: `<prefix>change<Role>Foreground`, with a `Dark` suffix in a dark theme. */
fun changeColorId(role: ChangeColorRole, dark: Boolean): String {
  val name = when (role) {
    ChangeColorRole.ADDED -> "changeAddedForeground"
    ChangeColorRole.DELETED -> "changeDeletedForeground"
    ChangeColorRole.RENAMED -> "changeRenamedForeground"
  }
  return CHANGE_COLOR_PREFIX + name + if (dark) "Dark" else ""
}
