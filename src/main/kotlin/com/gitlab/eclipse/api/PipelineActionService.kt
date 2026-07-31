package com.gitlab.eclipse.api

import com.gitlab.eclipse.inject.service

/**
 * Turns a pipeline action into its REST POST path and delegates to [GitLabApiClient.post],
 * pinned to the given [ConnectionSnapshot] (design §8.2: path assembly + pinned-connection
 * bridge). Pure path assembly + delegation: no retry/exception handling here — exceptions from
 * [GitLabApiClient.post] propagate to the caller.
 */
class PipelineActionService(private val apiClient: GitLabApiClient = service()) {
  fun retry(connection: ConnectionSnapshot, projectId: Long, pipelineId: Long): PostResult =
    apiClient.post("/projects/$projectId/pipelines/$pipelineId/retry", connection = connection)

  fun cancel(connection: ConnectionSnapshot, projectId: Long, pipelineId: Long): PostResult =
    apiClient.post("/projects/$projectId/pipelines/$pipelineId/cancel", connection = connection)
}
