package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.model.GitLabPipeline
import com.gitlab.eclipse.inject.service

/** Fetches pipeline information for a project. */
class PipelineService(private val apiClient: GitLabApiClient = service()) {
  /**
   * Fetches the most recent pipeline for [ref] in the project identified by [projectId]
   * (already URL-encoded; not re-encoded here). Returns `null` if the project has no
   * pipelines for that ref.
   */
  fun getLatestPipelineForRef(projectId: String, ref: String): GitLabPipeline? {
    return apiClient.fetchObject(
      "/projects/$projectId/pipelines",
      mapOf("ref" to ref, "per_page" to "1"),
      Array<GitLabPipeline>::class.java,
    ).firstOrNull()
  }
}
