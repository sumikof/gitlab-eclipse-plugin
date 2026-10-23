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

/** Text drawn for [node]: a changed file reads `<name> · <letter>` (name = last path segment in the tree); others use the label. */
fun sidebarDisplayText(node: SidebarNode): String {
  val badge = changeBadge(node) ?: return node.label
  val name = if ((node as ChangedFileNode).inTree) node.label.substringAfterLast('/') else node.label
  return "$name · ${badge.letter}"
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
