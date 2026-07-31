package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.CiHttpStatus
import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.api.model.GitLabJob
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.api.model.GitLabMrVersion
import com.gitlab.eclipse.api.model.GitLabPipeline
import com.gitlab.eclipse.ci.CiAction
import com.gitlab.eclipse.ci.CiStatus
import com.gitlab.eclipse.mergerequests.CurrentBranchInfo
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

/**
 * Pure composition logic for the sidebar's two query roots ("Issues assigned to me",
 * "Merge requests assigned to me"): turns the raw fetch results into [SidebarNode]s,
 * grouping by project in [SidebarViewMode.TREE] and isolating each root's failure from
 * the other's.
 */
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
   */
  fun buildMrChildren(
    webUrl: String,
    versionResult: Result<GitLabMrVersion?>,
    mode: SidebarViewMode,
  ): List<SidebarNode> =
    listOf(OverviewNode(webUrl)) +
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
