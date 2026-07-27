package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.api.model.GitLabMergeRequest

/**
 * Node in the sidebar tree (query roots, project groups, issues, merge requests,
 * status messages, and — from PR-3 — changed files/directories).
 *
 * Kept `sealed` so the content provider can dispatch on `children` generically
 * without a `when` over concrete types; new leaf/branch kinds are added here
 * without touching the provider.
 */
sealed interface SidebarNode {
  val label: String
  val children: List<SidebarNode>

  /** URL to open when the node is activated (double-click / Enter). Null when not activatable. */
  val activationUrl: String? get() = null
}

/** Top-level grouping node for a saved/quick query (e.g. "Assigned to me"). */
class QueryRootNode(
  override val label: String,
  override val children: List<SidebarNode>,
) : SidebarNode

/** Groups nodes under a project (used when a query spans multiple projects). */
class ProjectGroupNode(
  override val label: String,
  override val children: List<SidebarNode>,
) : SidebarNode

/** Leaf node representing a single GitLab issue. */
class IssueNode(issue: GitLabIssue) : SidebarNode {
  val url: String = issue.webUrl
  override val label: String = "${issue.references?.full ?: "#${issue.iid}"}  ${issue.title}"
  override val children: List<SidebarNode> = emptyList()
  override val activationUrl: String? = url
}

/** Leaf node representing a single GitLab merge request. */
class MergeRequestNode(mr: GitLabMergeRequest) : SidebarNode {
  val url: String = mr.webUrl
  override val label: String = "${mr.references?.full ?: "!${mr.iid}"}  ${mr.title}"
  override val children: List<SidebarNode> = emptyList()
  override val activationUrl: String? = url
}

/** Non-activatable informational leaf (loading / error / empty-state placeholder). */
class MessageNode(override val label: String) : SidebarNode {
  override val children: List<SidebarNode> = emptyList()
}

/**
 * Top-level section (PR-2, design doc §8.3) for the merge request open on the currently
 * checked-out branch and the issues it would close. Rendered as a third pseudo-root
 * alongside [buildRoots]'s two [QueryRootNode]s (Task 7 wires it into the view).
 */
class CurrentBranchSectionNode(override val children: List<SidebarNode>) : SidebarNode {
  override val label: String = "For current branch"
  override val activationUrl: String? = null
}

/** Kind of change a [ChangedFileNode] represents, mirroring GitLab diff status. */
enum class ChangeType { NEW, DELETED, RENAMED, MODIFIED }

/**
 * PR-3 extension point (design doc §7.2): leaf node for a single changed file in a
 * merge-request diff view. Declared now — and included in the sealed hierarchy — so
 * `SidebarContentProvider` (which walks `node.children` generically) does not need to
 * change when PR-3 starts producing these. Unused in PR-1.
 */
class ChangedFileNode(
  val oldPath: String?,
  val newPath: String?,
  val changeType: ChangeType,
  val diffHeadSha: String?,
) : SidebarNode {
  override val label: String = newPath ?: oldPath ?: ""
  override val children: List<SidebarNode> = emptyList()
}

/**
 * PR-3 extension point (design doc §7.2): branch node grouping [ChangedFileNode]s under a
 * directory path. Declared now for the same reason as [ChangedFileNode]; unused in PR-1.
 */
class ChangedDirectoryNode(
  override val label: String,
  override val children: List<SidebarNode>,
) : SidebarNode
