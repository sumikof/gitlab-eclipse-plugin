package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.api.model.GitLabMrVersion
import com.gitlab.eclipse.inject.service

/** Fetches merge requests: assigned to the user, for a source branch, and a merge request's closing issues. */
class MergeRequestService(private val apiClient: GitLabApiClient = service()) {
  fun getMergeRequestsAssignedToMe(): List<GitLabMergeRequest> {
    val request = ApiRequest(
      path = "/merge_requests",
      query = mapOf("scope" to "assigned_to_me", "state" to "opened"),
      elementType = GitLabMergeRequest::class.java,
    )
    return apiClient.fetchListFromApi(request).distinctBy { it.id }
  }

  /**
   * Finds open merge requests whose source branch is [sourceBranch], via the GLOBAL
   * `/merge_requests` endpoint (scope=all). This is used instead of a project-scoped
   * endpoint because forks may open MRs against a different target project than the
   * repository root; the caller is responsible for filtering results by
   * `sourceProjectId`.
   */
  fun findOpenMrsForBranch(sourceBranch: String): List<GitLabMergeRequest> {
    val request = ApiRequest(
      path = "/merge_requests",
      query = mapOf("scope" to "all", "state" to "opened", "source_branch" to sourceBranch),
      elementType = GitLabMergeRequest::class.java,
    )
    return apiClient.fetchListFromApi(request)
  }

  /**
   * Fetches the issues that merge request [mrIid] would close, in the project identified
   * by [encodedProjectId] (already URL-encoded; the caller passes the MR's own — i.e.
   * target — project id, not the source project).
   */
  fun getClosesIssues(encodedProjectId: String, mrIid: Long): List<GitLabIssue> {
    val request = ApiRequest(
      path = "/projects/$encodedProjectId/merge_requests/$mrIid/closes_issues",
      elementType = GitLabIssue::class.java,
    )
    return apiClient.fetchListFromApi(request)
  }

  /**
   * Fetches the changed files (diffs) of the latest diff version of merge request
   * [mrIid], in the project identified by [encodedProjectId] (already URL-encoded;
   * the caller passes the MR's own project id). Mirrors VSCode's two-call sequence
   * (gitlab_service.ts:344-350): list versions (newest first, no diffs), then fetch
   * the first entry's full detail (which includes diffs). Returns `null` if the MR
   * has no versions.
   *
   * When [connection] is non-null, BOTH requests are pinned to that same snapshot, so a
   * settings change between them cannot split the pair across instances or accounts — and the
   * caller can truthfully tag the result with the connection it was fetched over. `null` keeps
   * the pre-existing per-request global-reading behavior, unchanged.
   */
  fun getLatestMrVersion(
    encodedProjectId: String,
    mrIid: Long,
    connection: ConnectionSnapshot? = null,
  ): GitLabMrVersion? {
    val versions = apiClient.fetchListFromApi(
      ApiRequest(
        path = "/projects/$encodedProjectId/merge_requests/$mrIid/versions",
        elementType = GitLabMrVersion::class.java,
      ),
      connection = connection,
    )
    val latest = versions.firstOrNull() ?: return null
    return apiClient.fetchObject(
      "/projects/$encodedProjectId/merge_requests/$mrIid/versions/${latest.id}",
      type = GitLabMrVersion::class.java,
      connection = connection,
    )
  }
}
