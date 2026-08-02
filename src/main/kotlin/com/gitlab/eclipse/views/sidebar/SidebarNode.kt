package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.api.model.GitLabJob
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.api.model.GitLabNotePermissions
import com.gitlab.eclipse.api.model.GitLabNotePosition
import com.gitlab.eclipse.api.model.GitLabPipeline
import com.gitlab.eclipse.ci.CiStatus

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

/**
 * Shared loading placeholder text (U+2026 ellipsis): under a [MergeRequestNode] until its
 * changed files have been fetched, and — same glyph, one constant (Task 9 consolidation) —
 * for the pending query roots / section sides that [SidebarRefreshCoordinator] and
 * [SidebarViewModel] render while a fetch is still in flight.
 */
internal const val LOADING_MESSAGE = "Loading…"

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

  // Non-activatable: the node expands to show its "Overview" child (which opens the MR
  // in the browser) + changed files. Keeping activationUrl null avoids a double-click
  // both toggling expansion and opening a browser tab. `url` is still used to build the
  // Overview node and to resolve the MR's repo (VSCode parity: MR node expands, Overview opens).
  override val activationUrl: String? = null

  /**
   * Lazily-loaded children, written by the view on the SWT UI thread only (same
   * discipline as the view's result caches). `null` = not loaded yet.
   */
  var loadedChildren: List<SidebarNode>? = null

  // One stable instance: JFace tracks tree elements by identity, so returning a fresh
  // MessageNode from every children read would churn the widget mapping.
  private val loadingPlaceholder: List<SidebarNode> = listOf(MessageNode(LOADING_MESSAGE))

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

/**
 * Root node for a single GitLab pipeline (design doc §6.5/§7.2): expands into a
 * Pipeline→Stage→Job subtree built by [SidebarViewModel.buildPipelineNode].
 *
 * Carries what the context-menu handlers need (design doc §8.4/§8.5): the numeric ids to
 * address the REST retry/cancel endpoints, [canRetry]/[canCancel] eligibility derived from
 * the jobs' statuses, and the non-secret source-connection tags ([sourceInstanceUrl] +
 * [sourceAuthFingerprint]) identifying the connection the pipeline was actually fetched
 * over, so a write can refuse to run against a different instance/account.
 */
class PipelineNode(
  pipeline: GitLabPipeline,
  override val children: List<SidebarNode>,
  val canRetry: Boolean,
  val canCancel: Boolean,
  val sourceInstanceUrl: String,
  val sourceAuthFingerprint: String,
) : SidebarNode {
  val pipelineId: Long = pipeline.id
  val projectId: Long? = pipeline.projectId
  override val label: String = "Pipeline #${pipeline.id} · ${CiStatus.displayName(pipeline.status)}"
  override val activationUrl: String? = pipeline.webUrl
}

/** Groups a pipeline's [JobNode]s under their CI stage name (or `NO_STAGE` when absent). */
class StageNode(override val label: String, override val children: List<SidebarNode>) : SidebarNode

/**
 * Leaf node for a single job within a pipeline stage. Carries [projectId] plus the same
 * write-routing source-connection tags as [PipelineNode]; per-job retry/cancel/play
 * eligibility is computed later from [job]'s status (PropertyTester), not stored here.
 */
class JobNode(
  val job: GitLabJob,
  val projectId: Long?,
  val sourceInstanceUrl: String,
  val sourceAuthFingerprint: String,
) : SidebarNode {
  override val label: String = "${job.name ?: "(job)"} · ${CiStatus.displayName(job.status, job.allowFailure ?: false)}"
  override val children: List<SidebarNode> = emptyList()
  override val activationUrl: String? = job.webUrl
}

/**
 * Lazy-load state of a [DiscussionsSectionNode]'s children.
 *
 * UI-thread-confined: like the node's other mutable state, this is only ever read and written
 * on the SWT UI thread, so no memory-visibility annotation or lock is needed.
 */
enum class DiscussionLoadState { NOT_LOADED, LOADING, LOADED, FAILED }

/**
 * Expandable "Discussions" section under a [MergeRequestNode]: its [ThreadNode] children are
 * fetched lazily on first expansion, and until then a stable "Loading…" placeholder renders,
 * which also makes `SidebarContentProvider.hasChildren` report `true` so the expander (twistie)
 * shows before anything is loaded — the same discipline [MergeRequestNode] follows.
 *
 * Carries the merge request's identifiers ([projectId], [mrIid], [mrGid], [mrSha],
 * [namespaceWithPath]) and the non-secret source-connection tags ([sourceInstanceUrl] +
 * [sourceAuthFingerprint], the instance **and** account the data was actually fetched over),
 * duplicated onto the node because JFace tree selections are flat — `getParent` returns null,
 * so a context-menu handler cannot walk from a selected node back up to its merge request.
 * A later write compares both tags against the live connection and refuses to send when either
 * differs. Never log [sourceAuthFingerprint]: it identifies a credential.
 */
class DiscussionsSectionNode(
  val sourceInstanceUrl: String,
  val sourceAuthFingerprint: String,
  val projectId: Long,
  val mrIid: Long,
  val mrGid: String,
  val mrSha: String?,
  val namespaceWithPath: String,
) : SidebarNode {
  override val label: String = "Discussions"

  // Non-activatable: the node only expands; there is nothing to open in a browser for it.
  override val activationUrl: String? = null

  /**
   * Whether the current user may add a note to this merge request, as reported by the fetch.
   * Written by the view on the SWT UI thread only (same discipline as [loadedChildren]).
   */
  var canCreateNote: Boolean = false

  /**
   * Lazy-load progress of [loadedChildren]. Written by the view on the SWT UI thread only:
   * every read and write happens on the single serial UI thread, so there is no window between
   * a check and an update and therefore no need for `@Volatile` or synchronization.
   */
  var loadState: DiscussionLoadState = DiscussionLoadState.NOT_LOADED

  /**
   * Lazily-loaded children, written by the view on the SWT UI thread only (same discipline as
   * [MergeRequestNode.loadedChildren]). `null` = not loaded yet.
   */
  var loadedChildren: List<SidebarNode>? = null

  // One stable instance: JFace tracks tree elements by identity, so returning a fresh
  // MessageNode from every children read would churn the widget mapping.
  private val loadingPlaceholder: List<SidebarNode> = listOf(MessageNode(LOADING_MESSAGE))

  override val children: List<SidebarNode>
    get() = loadedChildren ?: loadingPlaceholder
}

/**
 * One discussion thread under a [DiscussionsSectionNode], expanding into its [NoteNode]s.
 *
 * [replyId] is the thread's reply handle (how a reply addresses this thread), [position] its
 * diff anchor (null for a comment on the merge request as a whole), and [permissions] what the
 * current user may do with the thread's first note. The connection tags and MR identifiers are
 * duplicated here for the same reason as on [DiscussionsSectionNode]: tree selections are flat.
 * Never log [sourceAuthFingerprint].
 */
class ThreadNode(
  val sourceInstanceUrl: String,
  val sourceAuthFingerprint: String,
  val projectId: Long,
  val mrIid: Long,
  val mrGid: String,
  val mrSha: String?,
  val namespaceWithPath: String,
  val replyId: String,
  val resolved: Boolean,
  val resolvable: Boolean,
  val position: GitLabNotePosition?,
  val permissions: GitLabNotePermissions,
  override val children: List<SidebarNode>,
) : SidebarNode {
  override val label: String = threadLocationLabel(position) + threadResolutionSuffix(resolved, resolvable)
}

/**
 * Leaf node for a single note (comment) inside a [ThreadNode].
 *
 * [replyId] is the *enclosing thread's* id — that is how a note-level action addresses its
 * thread without a parent pointer. It is deliberately not an object reference to the
 * [ThreadNode]: nodes are rebuilt on every refresh and a back-reference would keep a stale tree
 * alive. Connection tags and MR identifiers are duplicated for the flat-selection reason
 * documented on [DiscussionsSectionNode]. Never log [sourceAuthFingerprint] or [body].
 */
class NoteNode(
  val sourceInstanceUrl: String,
  val sourceAuthFingerprint: String,
  val projectId: Long,
  val mrIid: Long,
  val mrGid: String,
  val mrSha: String?,
  val namespaceWithPath: String,
  val noteGid: String,
  val replyId: String,
  val body: String,
  val authorUsername: String,
  val createdAt: String,
  val permissions: GitLabNotePermissions,
) : SidebarNode {
  override val label: String = noteNodeLabel(authorUsername, body)
  override val children: List<SidebarNode> = emptyList()
  override val activationUrl: String? = null
}

/** Label shown for a thread that is not anchored to a diff line. */
private const val THREAD_OVERALL_LABEL = "(overall)"

/** Maximum length of the one-line note summary rendered in the tree, before the ellipsis. */
private const val NOTE_SUMMARY_MAX_LENGTH = 120

/** Matches a run of whitespace, collapsed to a single space in a [NoteNode]'s one-line summary. */
private val whitespaceRun = Regex("\\s+")

/**
 * Location part of a [ThreadNode]'s label: the new-side path/line when the thread is anchored
 * there, else the old-side path/line (comments on deleted lines are readable even though
 * creating them is not supported), else [THREAD_OVERALL_LABEL].
 */
private fun threadLocationLabel(position: GitLabNotePosition?): String {
  if (position == null) return THREAD_OVERALL_LABEL
  val newPath = position.newPath
  if (newPath != null) {
    return if (position.newLine != null) "$newPath:${position.newLine}" else newPath
  }
  val oldPath = position.oldPath
  if (oldPath != null) {
    return if (position.oldLine != null) "$oldPath:${position.oldLine}" else oldPath
  }
  return THREAD_OVERALL_LABEL
}

/**
 * Resolution suffix of a [ThreadNode]'s label, using the same `·` separator as [PipelineNode]
 * and [JobNode]. An unresolvable thread has no resolution state to report, so it gets no suffix.
 */
private fun threadResolutionSuffix(resolved: Boolean, resolvable: Boolean): String = when {
  !resolvable -> ""
  resolved -> " · Resolved"
  else -> " · Unresolved"
}

/**
 * `"@author: summary"`, where the summary is the note body reduced to one line for the tree:
 * first line only, whitespace runs collapsed, trimmed, and truncated with an ellipsis (U+2026)
 * past [NOTE_SUMMARY_MAX_LENGTH]. A blank body yields just `"@author"`, with no trailing colon.
 */
private fun noteNodeLabel(authorUsername: String, body: String): String {
  val firstLine = body.takeWhile { it != '\n' && it != '\r' }
  val collapsed = firstLine.replace(whitespaceRun, " ").trim()
  if (collapsed.isEmpty()) return "@$authorUsername"
  val summary =
    if (collapsed.length > NOTE_SUMMARY_MAX_LENGTH) {
      collapsed.take(NOTE_SUMMARY_MAX_LENGTH) + "…"
    } else {
      collapsed
    }
  return "@$authorUsername: $summary"
}
