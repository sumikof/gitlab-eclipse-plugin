package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.model.GitLabProject
import com.gitlab.eclipse.inject.service

/** Fetches a single project's details. */
class ProjectDetailService(private val apiClient: GitLabApiClient = service()) {
  /**
   * When [connection] is non-null, the request is pinned to that snapshot instead of re-reading
   * the live preference store / token manager. `null` keeps the pre-existing global-reading
   * behavior, unchanged (Phase 5A §21.1).
   */
  fun getProject(encodedProjectId: String, connection: ConnectionSnapshot? = null): GitLabProject =
    apiClient.fetchObject(
      "/projects/$encodedProjectId",
      type = GitLabProject::class.java,
      connection = connection,
    )
}
