package com.gitlab.eclipse.mergerequests

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.MergeRequestService
import com.gitlab.eclipse.api.ProjectDetailService
import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.inject.service

/** Result of resolving the current branch's open merge request and its closing issues (§8.3). */
data class CurrentBranchInfo(val mr: GitLabMergeRequest?, val closesIssues: List<GitLabIssue>)

/**
 * Composes [MergeRequestService] and [ProjectDetailService] to resolve, for the currently
 * checked-out branch of the selected repository, its open merge request (if any) and the issues
 * that MR would close (Phase 3 §8.3). Pure logic over injected services — no I/O of its own; any
 * exception from a service call propagates to the caller uncaught.
 */
class CurrentBranchMrLookup(
  private val mrService: MergeRequestService = service(),
  private val projectDetail: ProjectDetailService = service(),
) {
  private val empty = CurrentBranchInfo(null, emptyList())

  /**
   * When [connection] is non-null, every one of the three service calls below is pinned to that
   * same snapshot, so a settings change mid-lookup cannot split them across instances or accounts.
   * `null` (the default) keeps the pre-existing per-call global-reading behavior, unchanged.
   *
   * [includeClosesIssues] `false` skips the closes_issues request and returns an empty list, for a
   * caller that only needs the MR and must not fail on that unrelated endpoint.
   */
  fun lookup(
    context: RepositoryContext,
    branch: CurrentBranch,
    connection: ConnectionSnapshot? = null,
    includeClosesIssues: Boolean = true,
  ): CurrentBranchInfo {
    val localName = branch.name ?: return empty

    val effectiveBranch = EffectiveRef.resolve(branch, context.remoteName) ?: localName

    val repoProjectId = projectDetail.getProject(context.projectId, connection).id
    val candidates = mrService.findOpenMrsForBranch(effectiveBranch, connection)
      .filter { it.sourceProjectId == repoProjectId }

    val shaMatch = branch.headSha?.let { headSha -> candidates.firstOrNull { it.sha == headSha } }
    val mr = shaMatch ?: candidates.maxByOrNull { it.updatedAt.orEmpty() } ?: return empty

    if (!includeClosesIssues) return CurrentBranchInfo(mr, emptyList())
    val closesIssues = mrService.getClosesIssues(mr.projectId.toString(), mr.iid, connection)
    return CurrentBranchInfo(mr, closesIssues)
  }
}

/** The current branch's open MR only, without its closing issues (the editor comment's G6 lookup). */
internal fun lookupMrOnly(
  lookup: CurrentBranchMrLookup,
  context: RepositoryContext,
  branch: CurrentBranch,
  connection: ConnectionSnapshot?,
): GitLabMergeRequest? = lookup.lookup(context, branch, connection, includeClosesIssues = false).mr
