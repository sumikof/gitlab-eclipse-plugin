package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.CiHttpStatus
import com.gitlab.eclipse.api.DiscussionService
import com.gitlab.eclipse.api.DiscussionsReadResult
import com.gitlab.eclipse.api.model.GitLabDiscussion
import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.api.model.GitLabJob
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.api.model.GitLabMrVersion
import com.gitlab.eclipse.api.model.GitLabNote
import com.gitlab.eclipse.api.model.GitLabPipeline
import com.gitlab.eclipse.ci.CiAction
import com.gitlab.eclipse.ci.CiStatus
import com.gitlab.eclipse.mergerequests.CurrentBranchInfo
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.issues.configErrorMessage

private const val NO_ISSUES_MESSAGE = "No issues assigned to you."
private const val NO_MRS_MESSAGE = "No merge requests assigned to you."
private const val LOAD_FAILED_MESSAGE = "Failed to load — see the Error Log."
private const val NO_CURRENT_BRANCH_MR_MESSAGE = "No merge request found"
private const val NO_CLOSING_ISSUE_MESSAGE = "No closing issue found"
private const val NO_CHANGED_FILES_MESSAGE = "No changed files"
private const val NO_STAGE = "(no stage)"
private const val JOBS_LOAD_FAILED_MESSAGE = "Failed to load jobs"

/** Shown in the "For current branch" section when no single repository can be resolved. */
private const val SELECT_REPOSITORY_MESSAGE = "Select a repository"
private const val PIPELINE_UNAVAILABLE_MESSAGE = "Unable to load pipeline"

/** Shown under a [DiscussionsSectionNode] whose merge request has no (non-system) discussions. */
private const val NO_DISCUSSIONS_MESSAGE = "No discussions"

/**
 * Last child of a [ThreadNode] whose notes were truncated server-side
 * ([GitLabDiscussion.hasMoreNotes]): this project never pages within a thread, so the marker is
 * what keeps the omission visible instead of silently dropping the remaining replies.
 */
private const val MORE_REPLIES_MESSAGE = "(more replies — open in GitLab)"

/**
 * Last child of a [DiscussionsSectionNode] whose fetch stopped early (page cap or deadline —
 * one wording for both, since the user's remedy is the same). Silent truncation is forbidden: a
 * user must never believe they are seeing every discussion when they are not.
 */
private const val DISCUSSIONS_TRUNCATED_MESSAGE = "(truncated — open the merge request in GitLab to see all discussions)"

/**
 * Failure children of a [DiscussionsSectionNode] when the load itself failed. Deliberately
 * generic about the cause: the failure can come from the fetch OR from reading the stored
 * credential before any request was issued, so it must not read as though the server were at
 * fault. Carries no exception detail — that lives in the Error Log, never in the tree.
 */
private const val DISCUSSIONS_LOAD_FAILED_MESSAGE = "Failed to load discussions — see the Error Log."

/**
 * Failure children of a [DiscussionsSectionNode] rejected by the connection gate (the instance
 * URL or the credential changed under the node): no request was ever sent, so this wording asks
 * for a refresh rather than reporting an error.
 */
private const val DISCUSSIONS_CONNECTION_CHANGED_MESSAGE = "Connection changed — refresh the view."

/** Audit prefix for a merge request rendered without its Discussions section (see [SidebarViewModel]). */
private const val DISCUSSIONS_SECTION_OMITTED_MESSAGE = "Discussions section omitted:"

/**
 * Pure composition logic for the sidebar's two query roots ("Issues assigned to me",
 * "Merge requests assigned to me"): turns the raw fetch results into [SidebarNode]s,
 * grouping by project in [SidebarViewMode.TREE] and isolating each root's failure from
 * the other's.
 */
// TooManyFunctions: the discussions builders (Task 9) push this class past detekt's 11-function
// threshold. Suppressed rather than restructured: the file's own top-level function count is at
// 10 of the same threshold, so moving these helpers out (the trick used by failureChildren /
// currentBranchChildren below) would only move the finding, and splitting the class would change
// every existing call site — both worse than one documented suppression on a pure, stateless
// composition class whose functions are deliberately small and independent.
@Suppress("TooManyFunctions")
class SidebarViewModel {

  fun buildRoots(
    issuesResult: Result<List<GitLabIssue>>,
    mrsResult: Result<List<GitLabMergeRequest>>,
    mode: SidebarViewMode,
  ): List<SidebarNode> =
    listOf(
      QueryRootNode(
        "Issues assigned to me",
        queryChildren(issuesResult, mode, NO_ISSUES_MESSAGE, { it.references?.full }, ::IssueNode),
      ),
      QueryRootNode(
        "Merge requests assigned to me",
        queryChildren(mrsResult, mode, NO_MRS_MESSAGE, { it.references?.full }, ::MergeRequestNode),
      ),
    )

  private fun <T> queryChildren(
    result: Result<List<T>>,
    mode: SidebarViewMode,
    emptyMessage: String,
    fullReference: (T) -> String?,
    toNode: (T) -> SidebarNode,
  ): List<SidebarNode> =
    result.fold(
      onSuccess = { items -> successChildren(items, mode, emptyMessage, fullReference, toNode) },
      onFailure = { error -> failureChildren(error) },
    )

  private fun <T> successChildren(
    items: List<T>,
    mode: SidebarViewMode,
    emptyMessage: String,
    fullReference: (T) -> String?,
    toNode: (T) -> SidebarNode,
  ): List<SidebarNode> {
    if (items.isEmpty()) return listOf(MessageNode(emptyMessage))
    return when (mode) {
      SidebarViewMode.LIST -> items.map(toNode)
      SidebarViewMode.TREE ->
        items
          .groupBy { projectKey(fullReference(it)) }
          .map { (project, group) -> ProjectGroupNode(project, group.map(toNode)) }
    }
  }

  /**
   * Builds the "For current branch" section (design doc §6.6/§8.3) from a
   * [CurrentBranchSectionInput] whose MR and pipeline lookups are independent async tasks: each
   * side renders its own "Loading…" placeholder while unsettled and its own failure message when
   * it fails, so a slow or failing pipeline fetch never hides an already-resolved MR/issues
   * list, and vice versa. The pipeline side is listed first per the design's §U-3 ordering; the
   * MR side renders via [currentBranchChildren]/[failureChildren] (through [Result.fold]).
   */
  fun buildCurrentBranchSection(input: CurrentBranchSectionInput): SidebarNode =
    when (input) {
      is NoRepository -> CurrentBranchSectionNode(listOf(MessageNode(SELECT_REPOSITORY_MESSAGE)))
      is Resolved ->
        CurrentBranchSectionNode(currentBranchResolvedChildren(input.pipeline, input.mr, ::buildPipelineNode))
    }

  /**
   * Builds a [PipelineNode] for [pipeline], grouping its jobs into a Pipeline→Stage→Job
   * subtree with a deterministic order: jobs are sorted by id ascending, then grouped by
   * stage in first-appearance order (stable regardless of the GitLab API's return order —
   * e.g. a retried job with a larger id still sorts into its stage's original position). A
   * failed [jobsResult] keeps the pipeline row and renders a single "Failed to load jobs"
   * child instead of the stage subtree (with retry/cancel eligibility off — no job
   * statuses to derive it from). [sourceInstanceUrl]/[sourceAuthFingerprint] are the
   * non-secret tags of the pinned connection the pipeline+jobs were fetched over
   * ([PipelineSnapshot]); they are stamped on the [PipelineNode] and every [JobNode] so
   * the write handlers can verify the connection has not changed underneath them.
   */
  fun buildPipelineNode(
    pipeline: GitLabPipeline,
    jobsResult: Result<List<GitLabJob>>,
    sourceInstanceUrl: String,
    sourceAuthFingerprint: String,
  ): PipelineNode {
    val children = jobsResult.fold(
      onSuccess = { jobs -> buildStageNodes(jobs, pipeline.projectId, sourceInstanceUrl, sourceAuthFingerprint) },
      onFailure = { listOf(MessageNode(JOBS_LOAD_FAILED_MESSAGE)) },
    )
    val jobs = jobsResult.getOrNull().orEmpty()
    return PipelineNode(
      pipeline,
      children,
      canRetry = jobs.any { CiStatus.contextAction(it.status, it.allowFailure ?: false) == CiAction.RETRYABLE },
      canCancel = jobs.any { CiStatus.contextAction(it.status, it.allowFailure ?: false) == CiAction.CANCELLABLE },
      sourceInstanceUrl = sourceInstanceUrl,
      sourceAuthFingerprint = sourceAuthFingerprint,
    )
  }

  /**
   * Children of an expanded [MergeRequestNode]: an [OverviewNode] (activates to the MR's
   * web page) followed by the changed files of its latest diff version, composed per
   * [mode]. A failed [versionResult] renders the same config-aware error message as the
   * query roots; a `null` version (MR with no diff versions) renders "No changed files".
   *
   * A [DiscussionsSectionNode] is inserted between the two (Overview → Discussions → changed
   * files) when — and only when — [mr] and both source-connection tags are supplied and the merge
   * request's `references.full` yields a namespace path. The three trailing parameters default to
   * `null` so the pre-existing three-argument call shape keeps producing exactly today's children;
   * see [discussionsSectionChildren] for what each omission means.
   */
  fun buildMrChildren(
    webUrl: String,
    versionResult: Result<GitLabMrVersion?>,
    mode: SidebarViewMode,
    mr: GitLabMergeRequest? = null,
    sourceInstanceUrl: String? = null,
    sourceAuthFingerprint: String? = null,
  ): List<SidebarNode> =
    listOf(OverviewNode(webUrl)) +
      discussionsSectionChildren(mr, sourceInstanceUrl, sourceAuthFingerprint) +
      versionResult.fold(
        onSuccess = { version -> buildChangedFileNodes(version, mode, webUrl) },
        onFailure = { error -> failureChildren(error) },
      )

  /**
   * Turns an MR version's diffs (design doc §7.2) into sidebar nodes: [SidebarViewMode.LIST]
   * is a flat, diff-order list of [ChangedFileNode]s; [SidebarViewMode.TREE] groups them into a
   * [ChangedDirectoryNode] hierarchy via [buildChangedFileTree]. A `null` [version] (the MR
   * has no diff versions at all) renders the same "No changed files" message as empty diffs.
   * [mrWebUrl] is the enclosing MR's web URL, stamped on each [ChangedFileNode] so
   * `OpenMrFileHandler` can match the node back to a workspace repository.
   */
  fun buildChangedFileNodes(
    version: GitLabMrVersion?,
    mode: SidebarViewMode,
    mrWebUrl: String? = null,
  ): List<SidebarNode> {
    val diffs = version?.let(::nullSafeDiffs) ?: emptyList()
    if (diffs.isEmpty()) return listOf(MessageNode(NO_CHANGED_FILES_MESSAGE))
    val fileNodes = diffs.map { diff -> toChangedFileNode(diff, version?.headCommitSha, mrWebUrl) }
    return when (mode) {
      SidebarViewMode.LIST -> fileNodes
      SidebarViewMode.TREE -> buildChangedFileTree(fileNodes)
    }
  }

  private fun toChangedFileNode(
    diff: GitLabMrVersion.Diff,
    headCommitSha: String?,
    mrWebUrl: String?,
  ): ChangedFileNode {
    val changeType =
      when {
        diff.deletedFile -> ChangeType.DELETED
        diff.newFile -> ChangeType.NEW
        diff.renamedFile -> ChangeType.RENAMED
        else -> ChangeType.MODIFIED
      }
    return ChangedFileNode(diff.oldPath, diff.newPath, changeType, headCommitSha, mrWebUrl)
  }

  /**
   * The [DiscussionsSectionNode] for [mr], or no children at all when it cannot be addressed
   * safely. Three omissions, all of them "render today's children unchanged" rather than an error
   * row, because none of them is something the user did wrong:
   *
   * - [mr] `null` — a caller that predates the section (the three-argument overload shape);
   * - either connection tag `null` — the connection could not be captured, so the node could not
   *   record which instance AND which account its data came from; a later write compares both
   *   against the live connection, and a section tagged with a guess would defeat that check;
   * - `mr.references?.full` `null` — [DiscussionService.namespaceWithPath] has no input, and the
   *   GraphQL query is addressed by namespace path, not by project id.
   *
   * [DiscussionService.mrGid] is built from `mr.id`, never `mr.iid`: they are different numbers
   * and the GID form requires the global id, so the wrong one would address a different merge
   * request on every write in the next PR.
   *
   * The audit lines carry a fixed reason token only — never the reference, the instance URL, or
   * the auth fingerprint — and the logger acquisition itself is guarded because this class is
   * pure, unit-tested code that must stay callable with no Eclipse bundle around it.
   */
  private fun discussionsSectionChildren(
    mr: GitLabMergeRequest?,
    sourceInstanceUrl: String?,
    sourceAuthFingerprint: String?,
  ): List<SidebarNode> {
    if (mr == null) return emptyList()
    if (sourceInstanceUrl == null || sourceAuthFingerprint == null) {
      logDiscussionsSectionOmitted("connectionTagsUnavailable")
      return emptyList()
    }
    val references = mr.references?.full
    if (references == null) {
      logDiscussionsSectionOmitted("mergeRequestReferenceUnavailable")
      return emptyList()
    }
    return listOf(
      DiscussionsSectionNode(
        sourceInstanceUrl = sourceInstanceUrl,
        sourceAuthFingerprint = sourceAuthFingerprint,
        projectId = mr.projectId,
        mrIid = mr.iid,
        mrGid = DiscussionService.mrGid(mr.id),
        mrSha = mr.sha,
        namespaceWithPath = DiscussionService.namespaceWithPath(references),
      ),
    )
  }

  /** Logs one omission reason, never failing the caller when no Eclipse log is available. */
  private fun logDiscussionsSectionOmitted(reason: String) {
    runCatching { logger<SidebarViewModel>().warn("$DISCUSSIONS_SECTION_OMITTED_MESSAGE reason=$reason") }
  }

  /**
   * Children of an expanded [DiscussionsSectionNode]: one [ThreadNode] per fetched discussion,
   * each holding its notes as [NoteNode]s in server order (which is reply order).
   *
   * Every truncation the fetch reports is made visible, because a user must never believe they
   * are seeing everything when they are not: a thread whose notes were cut short gets
   * [MORE_REPLIES_MESSAGE] as its last child, and a section whose fetch stopped at the page cap
   * or at the deadline gets [DISCUSSIONS_TRUNCATED_MESSAGE] as its last child — the same wording
   * for either [com.gitlab.eclipse.api.TruncationReason], since the user's remedy is identical.
   *
   * With no threads to show (no discussions, or every discussion defensively skipped) the section
   * renders [NO_DISCUSSIONS_MESSAGE]; a truncation marker still follows it, so "nothing here" and
   * "we stopped early" can never be confused for one another.
   */
  fun buildDiscussionChildren(node: DiscussionsSectionNode, result: DiscussionsReadResult): List<SidebarNode> {
    val threads = result.discussions.mapNotNull { discussion -> buildThreadNode(node, discussion) }
    val children = threads.ifEmpty { listOf(MessageNode(NO_DISCUSSIONS_MESSAGE)) }
    if (result.truncation == null) return children
    return children + MessageNode(DISCUSSIONS_TRUNCATED_MESSAGE)
  }

  /**
   * One [ThreadNode] for [discussion], carrying [node]'s connection tags and MR identifiers
   * verbatim. A thread's [GitLabNote.position] and [GitLabNote.permissions] come from its **first**
   * note because a thread is anchored where it was opened and replies carry no position of their
   * own. A discussion with no notes cannot reach here (the fetch drops those), but is skipped
   * rather than crashing the whole subtree if one ever does.
   */
  private fun buildThreadNode(node: DiscussionsSectionNode, discussion: GitLabDiscussion): ThreadNode? {
    val firstNote = discussion.notes.firstOrNull() ?: return null
    val notes = discussion.notes.map { note -> buildNoteNode(node, discussion.replyId, note) }
    return ThreadNode(
      sourceInstanceUrl = node.sourceInstanceUrl,
      sourceAuthFingerprint = node.sourceAuthFingerprint,
      projectId = node.projectId,
      mrIid = node.mrIid,
      mrGid = node.mrGid,
      mrSha = node.mrSha,
      namespaceWithPath = node.namespaceWithPath,
      replyId = discussion.replyId,
      resolved = discussion.resolved,
      resolvable = discussion.resolvable,
      position = firstNote.position,
      permissions = firstNote.permissions,
      children = if (discussion.hasMoreNotes) notes + MessageNode(MORE_REPLIES_MESSAGE) else notes,
    )
  }

  /**
   * One [NoteNode] for [note] under the thread identified by [replyId] — the *enclosing thread's*
   * reply handle, not the note's own id, which is how a note-level action addresses its thread
   * without a parent pointer (tree selections are flat).
   */
  private fun buildNoteNode(node: DiscussionsSectionNode, replyId: String, note: GitLabNote): NoteNode =
    NoteNode(
      sourceInstanceUrl = node.sourceInstanceUrl,
      sourceAuthFingerprint = node.sourceAuthFingerprint,
      projectId = node.projectId,
      mrIid = node.mrIid,
      mrGid = node.mrGid,
      mrSha = node.mrSha,
      namespaceWithPath = node.namespaceWithPath,
      noteGid = note.id,
      replyId = replyId,
      body = note.body,
      authorUsername = note.authorUsername,
      createdAt = note.createdAt,
      permissions = note.permissions,
    )

  /**
   * Failure children of a [DiscussionsSectionNode]. [gateRejected] `true` is the connection gate
   * (instance URL or credential changed, nothing was sent) and asks for a refresh; `false` is
   * everything else — including a failure to read the stored credential, which is why the wording
   * blames neither the server nor the user.
   */
  fun buildDiscussionFailureChildren(gateRejected: Boolean): List<SidebarNode> =
    listOf(MessageNode(if (gateRejected) DISCUSSIONS_CONNECTION_CHANGED_MESSAGE else DISCUSSIONS_LOAD_FAILED_MESSAGE))

  /**
   * Placeholder children shown while a discussions fetch is in flight, reusing the one shared
   * [LOADING_MESSAGE] constant — there is deliberately no second ellipsis string in this plugin.
   */
  fun buildDiscussionLoadingChildren(): List<SidebarNode> = listOf(MessageNode(LOADING_MESSAGE))
}

/**
 * Shared failure rendering for a query root / current-branch section (design doc): a single
 * [MessageNode] with a config-aware message when [error] indicates a missing/invalid GitLab
 * configuration, or the generic [LOAD_FAILED_MESSAGE] otherwise.
 *
 * Top-level rather than a class member — same [TooManyFunctions] reason as [nullSafeDiffs]
 * below (Task 6 review): [SidebarViewModel] has no instance state, so moving this pure helper
 * out of the class is a behavior-preserving move, not a refactor of its callers.
 */
private fun failureChildren(error: Throwable): List<SidebarNode> =
  listOf(MessageNode(configErrorMessage(error) ?: LOAD_FAILED_MESSAGE))

/**
 * Children of the "For current branch" section's MR side (design doc §8.3): the open merge
 * request for the currently checked-out branch (if any) and the issues it would close. Moved
 * top-level for the same [TooManyFunctions] reason as [failureChildren]; used unqualified from
 * [currentBranchResolvedChildren] below.
 */
private fun currentBranchChildren(info: CurrentBranchInfo): List<SidebarNode> {
  val mr = info.mr ?: return listOf(MessageNode(NO_CURRENT_BRANCH_MR_MESSAGE))
  val issueNodes =
    info.closesIssues
      .map(::IssueNode)
      .ifEmpty { listOf(MessageNode(NO_CLOSING_ISSUE_MESSAGE)) }
  return listOf(MergeRequestNode(mr)) + issueNodes
}

/**
 * Combined pipeline+MR children of "For current branch" when a repository IS resolved (design
 * doc §6.6/§U-3, the `Resolved` variant; pipeline listed first). The two sides are independent:
 *
 * - Pipeline: `null` means the fetch hasn't settled yet ("Loading…" placeholder); a settled
 *   `null` snapshot means there is no current pipeline (no row at all — 0 pipelines isn't an
 *   error); a settled non-null snapshot renders via [buildPipelineNode]
 *   ([SidebarViewModel.buildPipelineNode], passed in because that method lives on the class and
 *   this helper does not — see the [TooManyFunctions] note on [failureChildren]); a failure
 *   renders [PIPELINE_UNAVAILABLE_MESSAGE] for an access-denied response (403/404) or the
 *   generic [LOAD_FAILED_MESSAGE] otherwise, so a 403 never leaks GitLab-side error detail.
 * - MR: `null` means the lookup hasn't settled yet ("Loading…" placeholder); otherwise
 *   [currentBranchChildren] on success, [failureChildren] on failure — the same rendering the
 *   pre-Task-9 `Result`-based overload produced, so PR-2's manually-verified behavior is kept.
 *
 * Kept as one function (rather than a pipeline-only and an MR-only helper) to stay under the
 * file's top-level [TooManyFunctions] threshold alongside [failureChildren] and
 * [currentBranchChildren] (also moved top-level for the class-level threshold).
 */
private fun currentBranchResolvedChildren(
  pipeline: Result<PipelineSnapshot?>?,
  mr: Result<CurrentBranchInfo>?,
  buildPipelineNode: (GitLabPipeline, Result<List<GitLabJob>>, String, String) -> PipelineNode,
): List<SidebarNode> {
  val pipelineChildren =
    when {
      pipeline == null -> listOf(MessageNode(LOADING_MESSAGE))
      else ->
        pipeline.fold(
          onSuccess = { snapshot ->
            snapshot
              ?.let {
                listOf(buildPipelineNode(it.pipeline, it.jobsResult, it.sourceInstanceUrl, it.sourceAuthFingerprint))
              }
              ?: emptyList()
          },
          onFailure = { error ->
            val message = if (CiHttpStatus.isAccessDenied(error)) PIPELINE_UNAVAILABLE_MESSAGE else LOAD_FAILED_MESSAGE
            listOf(MessageNode(message))
          },
        )
    }
  val mrChildren =
    when {
      mr == null -> listOf(MessageNode(LOADING_MESSAGE))
      else ->
        mr.fold(
          onSuccess = { info -> currentBranchChildren(info) },
          onFailure = { error -> failureChildren(error) },
        )
    }
  return pipelineChildren + mrChildren
}

/**
 * [GitLabMrVersion.diffs]'s `= emptyList()` default is decorative: Gson populates fields via
 * unsafe object construction and ignores Kotlin defaults, so a version built from raw JSON
 * lacking a `diffs` key ends up with a `null` field despite the non-nullable declared type
 * (Task 3 review). Guard against that here rather than trusting the compile-time type.
 */
@Suppress("SENSELESS_COMPARISON")
private fun nullSafeDiffs(version: GitLabMrVersion): List<GitLabMrVersion.Diff> =
  if (version.diffs == null) emptyList() else version.diffs

/**
 * Groups a pipeline's jobs into [StageNode]s with a deterministic order: sorted by id
 * ascending, then grouped by stage in first-appearance order (a [LinkedHashMap] via
 * [groupBy], so a retried job with a larger id still lands in its stage's original slot).
 * Each [JobNode] carries the enclosing pipeline's [projectId] and the source-connection
 * tags the jobs were fetched with (see [PipelineNode]'s KDoc).
 */
private fun buildStageNodes(
  jobs: List<GitLabJob>,
  projectId: Long?,
  sourceInstanceUrl: String,
  sourceAuthFingerprint: String,
): List<SidebarNode> =
  jobs.sortedBy { it.id }
    .groupBy { it.stage ?: NO_STAGE }
    .map { (stage, stageJobs) ->
      StageNode(stage, stageJobs.map { JobNode(it, projectId, sourceInstanceUrl, sourceAuthFingerprint) })
    }

/** Groups items by project, keyed off the `namespace/path#iid` or `namespace/path!iid` reference. */
internal fun projectKey(full: String?): String =
  full?.substringBefore('#')?.substringBefore('!')?.trim()?.ifBlank { null } ?: "Unknown project"

/**
 * Groups flat [ChangedFileNode]s into a [ChangedDirectoryNode] hierarchy (TREE mode), collapsing
 * single-child directory chains for parity with VSCode's `ChangedFolderItem` (the merge is
 * `changed_folder_item.ts:38-47` in `./out/gitlab-vscode-extension`; the task brief cites
 * `:181-190`, likely a different revision — same "concatenate folder names" logic): a directory
 * holding no files of its own and exactly one distinct subfolder is merged with that subfolder
 * into a single node, repeated until the chain ends. E.g. `src/main/A.kt` + `src/main/B.kt`
 * yields one `src/main` node with two file children, and a lone `x/y/z/one.kt` yields one
 * `x/y/z` node (not three single-child levels).
 */
internal fun buildChangedFileTree(fileNodes: List<ChangedFileNode>): List<SidebarNode> =
  groupChangedFileEntries(fileNodes.map { node -> node.label.split('/') to node })

/** A changed-file entry paired with the path segments still to be grouped/consumed. */
private typealias ChangedFileEntry = Pair<List<String>, ChangedFileNode>

private fun groupChangedFileEntries(entries: List<ChangedFileEntry>): List<SidebarNode> {
  val files = entries.filter { (segments, _) -> segments.size == 1 }.map { (_, node) -> node }
  val subfolders = entries.filter { (segments, _) -> segments.size > 1 }
  val subfolderNames = subfolders.map { (segments, _) -> segments.first() }.distinct()
  val directoryNodes =
    subfolderNames.map { name -> buildChangedDirectoryNode(name, childEntriesOf(subfolders, name)) }
  return directoryNodes + files
}

private fun buildChangedDirectoryNode(name: String, entries: List<ChangedFileEntry>): ChangedDirectoryNode {
  val files = entries.filter { (segments, _) -> segments.size == 1 }.map { (_, node) -> node }
  val subfolders = entries.filter { (segments, _) -> segments.size > 1 }
  val subfolderNames = subfolders.map { (segments, _) -> segments.first() }.distinct()

  if (files.isEmpty() && subfolderNames.size == 1) {
    val soleSubfolder = subfolderNames.first()
    return buildChangedDirectoryNode("$name/$soleSubfolder", childEntriesOf(subfolders, soleSubfolder))
  }

  val subfolderNodes =
    subfolderNames.map { subfolder -> buildChangedDirectoryNode(subfolder, childEntriesOf(subfolders, subfolder)) }
  return ChangedDirectoryNode(name, subfolderNodes + files)
}

/** Entries under [name] with that leading path segment consumed, ready for the next grouping level. */
private fun childEntriesOf(entries: List<ChangedFileEntry>, name: String): List<ChangedFileEntry> =
  entries
    .filter { (segments, _) -> segments.first() == name }
    .map { (segments, node) -> segments.drop(1) to node }
