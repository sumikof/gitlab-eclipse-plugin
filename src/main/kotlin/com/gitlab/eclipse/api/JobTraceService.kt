package com.gitlab.eclipse.api

import com.gitlab.eclipse.inject.service

/**
 * Fetches a single job's raw trace log, pinned to a [ConnectionSnapshot] (design §8.1). Pure path
 * assembly + delegation to [GitLabApiClient.fetchText]; no formatting (see stripTraceFormatting) and
 * no exception handling here — exceptions from the API client propagate to the caller.
 */
class JobTraceService(private val apiClient: GitLabApiClient = service()) {
  fun getTrace(projectId: Long, jobId: Long, connection: ConnectionSnapshot): String =
    apiClient.fetchText("/projects/$projectId/jobs/$jobId/trace", connection)
}
