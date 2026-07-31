package com.gitlab.eclipse.api

import com.gitlab.eclipse.inject.service

/**
 * Turns a job action into its REST POST path and delegates to [GitLabApiClient.post], pinned
 * to the given [ConnectionSnapshot] (design §8.2: path assembly + pinned-connection bridge).
 * Pure path assembly + delegation: no retry/exception handling here — exceptions from
 * [GitLabApiClient.post] propagate to the caller.
 */
class JobActionService(private val apiClient: GitLabApiClient = service()) {
  fun retry(connection: ConnectionSnapshot, projectId: Long, jobId: Long): PostResult =
    apiClient.post("/projects/$projectId/jobs/$jobId/retry", connection = connection)

  fun cancel(connection: ConnectionSnapshot, projectId: Long, jobId: Long): PostResult =
    apiClient.post("/projects/$projectId/jobs/$jobId/cancel", connection = connection)

  fun play(connection: ConnectionSnapshot, projectId: Long, jobId: Long): PostResult =
    apiClient.post("/projects/$projectId/jobs/$jobId/play", connection = connection)
}
