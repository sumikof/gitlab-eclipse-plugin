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

/** Shown under a [MergeRequestNode] until its changed files have been fetched. */
private const val MR_LOADING_MESSAGE = "Loading…"

/**
 * Expandable node representing a single GitLab merge request. Its children — an
 * [OverviewNode] plus the changed files of the MR's latest diff version — are fetched
 * lazily by [GitLabSidebarView] on first expansion; until then a stable "Loading…"
 * placeholder renders, which also makes [SidebarContentProvider.hasChildren] report
 * `true` so the expander (twistie) shows before anything is loaded.
 */
class MergeRequestNode(val mr: GitLabMergeRequest) : SidebarNode {
  val url: String = mr.webUrl
  override val label: String = "${mr.references?.full ?: "!${mr.iid}"}  ${mr.title}"
  override val activationUrl: String? = url

  /**
   * Lazily-loaded children, written by the view on the SWT UI thread only (same
   * discipline as the view's result caches). `null` = not loaded yet.
   */
  var loadedChildren: List<SidebarNode>? = null

  // One stable instance: JFace tracks tree elements by identity, so returning a fresh
  // MessageNode from every children read would churn the widget mapping.
  private val loadingPlaceholder: List<SidebarNode> = listOf(MessageNode(MR_LOADING_MESSAGE))

  override val children: List<SidebarNode>
    get() = loadedChildren ?: loadingPlaceholder
}

/**
 * First child of an expanded [MergeRequestNode] (VSCode parity: the "Overview" item):
 * activating it opens the merge request's overview page in the browser. A dedicated type —
 * rather than reusing [MessageNode] — keeps MessageNode's "never activatable" contract intact.
 */
class OverviewNode(webUrl: String) : SidebarNode {
  override val label: String = "Overview"
  override val children: List<SidebarNode> = emptyList()
  override val activationUrl: String? = webUrl
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
 * Leaf node for a single changed file in a merge-request diff view (design doc §7.2).
 *
 * [mrWebUrl] is the enclosing merge request's web URL, carried on the node because JFace
 * tree selections are flat (the PR-1 content provider's `getParent` returns null, so a
 * handler cannot walk from a selected file back to its [MergeRequestNode]): it is what
 * `OpenMrFileHandler` matches against a workspace repository's project web URL. [diffHeadSha]
 * is the diff version's head commit — the handler refuses to open a file unless the matched
 * repository's HEAD is exactly this commit.
 */
class ChangedFileNode(
  val oldPath: String?,
  val newPath: String?,
  val changeType: ChangeType,
  val diffHeadSha: String?,
  val mrWebUrl: String? = null,
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
