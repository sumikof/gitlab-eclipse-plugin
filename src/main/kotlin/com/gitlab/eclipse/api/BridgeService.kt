package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.model.GitLabBridge
import com.gitlab.eclipse.inject.service
import java.time.Duration

/** Fetches the bridge (trigger) jobs of a pipeline. */
class BridgeService(private val apiClient: GitLabApiClient = service()) {
  /**
   * Fetches all bridges of pipeline [pipelineId] in the project identified by [projectId]
   * (already URL-encoded; not re-encoded here). Uses `/bridges` rather than `/trigger_jobs`
   * because the former works on every supported instance (deprecated in 19.2 but served by
   * the same handler). Bounded by [BRIDGES_DEADLINE], the same budget as jobs. [isActive] is
   * checked between pages; when [connection] is given EVERY page is pinned to that snapshot.
   * API errors (e.g. 403/404 for a project the user cannot read) propagate unchanged.
   */
  fun getBridgesForPipeline(
    projectId: String,
    pipelineId: Long,
    isActive: () -> Boolean = { true },
    connection: ConnectionSnapshot? = null,
  ): List<GitLabBridge> {
    return apiClient.fetchListWithinDeadline(
      ApiRequest(
        path = "/projects/$projectId/pipelines/$pipelineId/bridges",
        elementType = GitLabBridge::class.java,
      ),
      BRIDGES_DEADLINE,
      isActive = isActive,
      connection = connection,
    )
  }

  companion object {
    private val BRIDGES_DEADLINE = Duration.ofSeconds(15)
  }
}
