package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.model.GitLabBridge
import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.api.model.GitLabJob
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.api.model.GitLabNotePermissions
import com.gitlab.eclipse.api.model.GitLabNotePosition
import com.gitlab.eclipse.api.model.GitLabPipeline
import com.gitlab.eclipse.ci.CiStatus
import com.gitlab.eclipse.ci.PipelineKind
import com.gitlab.eclipse.ci.aggregateStatus
import com.gitlab.eclipse.mergerequests.diff.MrDiffSource
import com.gitlab.eclipse.mergerequests.review.MergeRequestRef

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

/**
 * Top-level node of one sidebar query (ISSUE-01 design §7). [queryId] is the query's stable id (or a reserved
 * `__unreadable` / `__empty` id for the config-level roots); expansion is remapped by it, so two roots with the same
 * [label] never collide and a rename or reorder keeps the root expanded.
 */
class QueryRootNode(
  val queryId: String,
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
 * checked-out branch and the issues it would close. Rendered as the last pseudo-root
 * after the [QueryRootNode]s.
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
 *
 * [mrRef] addresses the enclosing merge request for the editor review session the handler begins
 * once the file is open (editor-thread-ui design §8.3, FR-1). It is non-null exactly when the
 * sibling [DiscussionsSectionNode] could be built — same connection tags, same GID and namespace —
 * and `null` otherwise, in which case the file opens as before without a session.
 *
 * [diffSource] holds the version's base/start/head SHAs, the target project and iid, and the
 * connection tags the list was fetched with, so the node alone can open a base↔head diff
 * (REVIEW-01 design §5.2). Unlike [mrRef] it does not require `references.full`; it is `null` only
 * when the MR or a connection tag was unavailable. [aMode]/[bMode] are the diff's git file modes.
 */
class ChangedFileNode(
  val oldPath: String?,
  val newPath: String?,
  val changeType: ChangeType,
  val diffHeadSha: String?,
  val mrWebUrl: String? = null,
  val mrRef: MergeRequestRef? = null,
  val diffSource: MrDiffSource? = null,
  val aMode: String? = null,
  val bMode: String? = null,
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
 * Pipeline→Stage→Job/Bridge subtree, followed by the "Downstream pipelines" group when any bridge
 * started one (CI-06), built by [SidebarViewModel.buildPipelineNode].
 *
 * Carries what the context-menu handlers need (design doc §8.4/§8.5): the numeric ids to
 * address the REST retry/cancel endpoints, [canRetry]/[canCancel] eligibility derived from
 * the latest job attempts' and the bridges' statuses, and the non-secret source-connection tags
 * ([sourceInstanceUrl] + [sourceAuthFingerprint]) identifying the connection the pipeline was
 * actually fetched over, so a write can refuse to run against a different instance/account.
 * [kind] only changes the label: a tag pipeline reads `Tag Pipeline #<id>` (CI-05 §4.2), while MR
 * and branch pipelines are deliberately not told apart (VS Code parity, design D3).
 */
class PipelineNode(
  pipeline: GitLabPipeline,
  override val children: List<SidebarNode>,
  val canRetry: Boolean,
  val canCancel: Boolean,
  val sourceInstanceUrl: String,
  val sourceAuthFingerprint: String,
  kind: PipelineKind = PipelineKind.BRANCH,
) : SidebarNode {
  val pipelineId: Long = pipeline.id
  val projectId: Long? = pipeline.projectId
  private val kindPrefix: String = if (kind == PipelineKind.TAG) "Tag Pipeline" else "Pipeline"
  override val label: String = "$kindPrefix #${pipeline.id} · ${CiStatus.displayName(pipeline.status)}"
  override val activationUrl: String? = pipeline.webUrl
}

/**
 * Groups a pipeline's [JobNode]s and [BridgeNode]s under their CI stage [name] (or `NO_STAGE` when
 * absent). [statusName] is the stage's aggregate status (CI-05 §4.4: the display name of the
 * highest-priority child). The label carries it, so expansion is carried across a rebuild by
 * [name], never by [label] — otherwise every status change would collapse the stage.
 */
class StageNode(val name: String, val statusName: String, override val children: List<SidebarNode>) : SidebarNode {
  override val label: String = "$name · $statusName"
}

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
 * Leaf node for a trigger job (bridge, CI-06 §4.6), listed in its stage alongside the jobs. Same
 * write routing as [JobNode] ([projectId] = the project of the pipeline that holds it, plus the
 * source-connection tags), but a bridge affords only retry and play: it has no log or artifacts,
 * and GitLab's cancel endpoint answers 404 for it (`find_build!`), so those menus never show.
 * A separate type — rather than a flag on [JobNode] — is what keeps the log / artifacts / cancel
 * menus (`instanceof JobNode` in plugin.xml) and their handlers away from bridges.
 */
class BridgeNode(
  val bridge: GitLabBridge,
  val projectId: Long?,
  val sourceInstanceUrl: String,
  val sourceAuthFingerprint: String,
) : SidebarNode {
  override val label: String =
    "${bridge.name ?: "(bridge)"} · ${CiStatus.displayName(bridge.status, bridge.allowFailure ?: false)}"
  override val children: List<SidebarNode> = emptyList()
  override val activationUrl: String? = bridge.webUrl
}

/**
 * Non-lazy "Downstream pipelines" group (CI-06 §4.6): the pipelines the bridges of the pipeline
 * [parentPipelineId] started, built straight from that pipeline's bridges. The label carries the
 * highest-priority status among [children].
 */
class DownstreamPipelinesNode(
  val parentPipelineId: Long,
  override val children: List<DownstreamPipelineNode>,
) : SidebarNode {
  override val label: String = "Downstream pipelines · ${downstreamAggregateName(children)}"
}

/** Display name of the highest-priority pipeline status among [nodes] (ties: first); unknown when empty. */
private fun downstreamAggregateName(nodes: List<DownstreamPipelineNode>): String {
  val (status, allowFailure) = aggregateStatus(nodes.map { it.pipeline.status to false })
  return CiStatus.displayName(status, allowFailure)
}

/**
 * Deepest downstream level that may still expand (root pipeline = depth 0, its downstream
 * pipelines = 1). GitLab's own child-pipeline nesting limit defaults to 2, so this is only a guard
 * against a pathological chain, alongside the ancestor cycle check.
 */
const val MAX_DOWNSTREAM_DEPTH = 10

/** Lazy-load state of a [DownstreamPipelineNode]'s children. UI-thread-confined, like [DiscussionLoadState]. */
enum class DownstreamLoadState { NOT_LOADED, LOADING, LOADED, FAILED }

/**
 * What a [DownstreamPipelineNode]'s children were built from: its jobs (`include_retried=true`) and
 * bridges fetches, each settled on its own. Kept on the node ([DownstreamPipelineNode.shownPayload])
 * so a refresh sweep can skip rebuilding when a re-fetch is structurally equal.
 */
data class DownstreamPayload(
  val jobsResult: Result<List<GitLabJob>>,
  val bridgesResult: Result<List<GitLabBridge>>,
)

/**
 * One downstream (child or multi-project) pipeline started by the bridge [bridgeName] (CI-06 §4.6).
 * Its children — stages, and its own downstream group — are fetched lazily on expansion, from the
 * downstream's own project ([projectId] = `downstream_pipeline.project_id`) over the connection
 * named by the source tags, then built by [SidebarViewModel.buildDownstreamChildren].
 *
 * The node carries its [depth] and the ids of every pipeline above it ([ancestorPipelineIds])
 * because the content provider's `getParent` returns null: it is [expandable] only when its project
 * is known, it is not its own ancestor (a cycle), and it is no deeper than [MAX_DOWNSTREAM_DEPTH].
 * A non-expandable node is a leaf that still opens [GitLabPipeline.webUrl] on activation.
 *
 * [parentProjectId] is only used for the kind suffix when the instance omits `source`: a downstream
 * in another project is then labelled multi-project (design §3.6).
 */
class DownstreamPipelineNode(
  val bridgeName: String?,
  val pipeline: GitLabPipeline,
  val depth: Int,
  val ancestorPipelineIds: Set<Long>,
  val sourceInstanceUrl: String,
  val sourceAuthFingerprint: String,
  val parentProjectId: Long? = null,
) : SidebarNode {
  val pipelineId: Long = pipeline.id
  val projectId: Long? = pipeline.projectId

  val expandable: Boolean =
    projectId != null && pipelineId !in ancestorPipelineIds && depth <= MAX_DOWNSTREAM_DEPTH

  override val label: String =
    "${bridgeName ?: "(bridge)"} → Pipeline #${pipeline.id} · ${CiStatus.displayName(pipeline.status)}" +
      downstreamKindSuffix(pipeline, parentProjectId)

  override val activationUrl: String? = pipeline.webUrl

  /** Lazy-load progress of [loadedChildren]. Written by the view on the SWT UI thread only. */
  var loadState: DownstreamLoadState = DownstreamLoadState.NOT_LOADED

  /** Lazily-loaded children, written by the view on the SWT UI thread only. `null` = not loaded yet. */
  var loadedChildren: List<SidebarNode>? = null

  /** The payload [loadedChildren] were built from (UI thread only); `null` until loaded. */
  var shownPayload: DownstreamPayload? = null

  /**
   * Load generation (UI thread only): bumped when a load starts or is abandoned, so a result
   * carrying an older generation is dropped instead of applied.
   */
  var loadGeneration: Long = 0

  // One stable instance: JFace tracks tree elements by identity (see MergeRequestNode).
  private val loadingPlaceholder: List<SidebarNode> = listOf(MessageNode(LOADING_MESSAGE))

  override val children: List<SidebarNode>
    get() = if (!expandable) emptyList() else loadedChildren ?: loadingPlaceholder
}

/**
 * `" (child)"` / `" (multi-project)"` from `downstream_pipeline.source` (`parent_pipeline` /
 * `pipeline`); without a known source, a downstream in a different project than [parentProjectId]
 * is multi-project and one in the same project a child. Nothing when neither can be told.
 */
private fun downstreamKindSuffix(pipeline: GitLabPipeline, parentProjectId: Long?): String {
  val projectId = pipeline.projectId
  return when {
    pipeline.source == "parent_pipeline" -> " (child)"
    pipeline.source == "pipeline" -> " (multi-project)"
    projectId == null || parentProjectId == null -> ""
    projectId != parentProjectId -> " (multi-project)"
    else -> " (child)"
  }
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
  /**
   * Unique per-instance identity, folded into [com.gitlab.eclipse.mergerequests.discussions.DiscussionKey]
   * so two display nodes for the SAME merge request (it appears under both "Merge requests
   * assigned to me" and "For current branch") never share a generation slot: without it, the
   * second node's load supersedes the first's, which then — per the Superseded contract —
   * touches nothing and strands the first node in LOADING forever. A re-load of the same node
   * keeps superseding its own earlier load, because the id is stable for the node's lifetime.
   */
  val nodeId: Long = NODE_IDS.incrementAndGet()

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

  companion object {
    /** Monotonic [nodeId] source; atomic because nodes may be built off the UI thread. */
    private val NODE_IDS = java.util.concurrent.atomic.AtomicLong()
  }
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
