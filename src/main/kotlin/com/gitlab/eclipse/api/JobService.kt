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
   * aborts with a `CancellationException` instead of issuing further requests.
   */
  fun getJobsForPipeline(
    projectId: String,
    pipelineId: Long,
    isActive: () -> Boolean = { true },
  ): List<GitLabJob> {
    return apiClient.fetchListWithinDeadline(
      ApiRequest(
        path = "/projects/$projectId/pipelines/$pipelineId/jobs",
        elementType = GitLabJob::class.java,
      ),
      JOBS_DEADLINE,
      isActive = isActive,
    )
  }

  companion object {
    private val JOBS_DEADLINE = Duration.ofSeconds(15)
  }
}
