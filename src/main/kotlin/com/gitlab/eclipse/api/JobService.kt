package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.model.GitLabJob
import com.gitlab.eclipse.inject.service
import java.time.Duration

/** Fetches the jobs of a pipeline. */
class JobService(private val apiClient: GitLabApiClient = service()) {
  /**
   * Fetches all jobs of pipeline [pipelineId] in the project identified by [projectId]
   * (already URL-encoded; not re-encoded here). Bounded by [JOBS_DEADLINE] since a
   * pipeline can have many jobs across multiple pages. [isActive] is checked between
   * pages ([GitLabApiClient.fetchListWithinDeadline]): once it returns false the paging
   * aborts with a `CancellationException` instead of issuing further requests. When
   * [connection] is given, EVERY page is pinned to that snapshot instead of the live
   * preference store / token manager; `connection = null` (the default) keeps the current
   * global-reading behavior. Kept as the last parameter so the existing trailing-lambda call
   * site `getJobsForPipeline(projectId, pipelineId) { ... }` still compiles unchanged.
   */
  fun getJobsForPipeline(
    projectId: String,
    pipelineId: Long,
    isActive: () -> Boolean = { true },
    connection: ConnectionSnapshot? = null,
  ): List<GitLabJob> {
    return apiClient.fetchListWithinDeadline(
      ApiRequest(
        path = "/projects/$projectId/pipelines/$pipelineId/jobs",
        elementType = GitLabJob::class.java,
      ),
      JOBS_DEADLINE,
      isActive = isActive,
      connection = connection,
    )
  }

  companion object {
    private val JOBS_DEADLINE = Duration.ofSeconds(15)
  }
}
