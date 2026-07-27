package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.model.GitLabProject
import com.gitlab.eclipse.inject.service

/** Fetches a single project's details. */
class ProjectDetailService(private val apiClient: GitLabApiClient = service()) {
  fun getProject(encodedProjectId: String): GitLabProject =
    apiClient.fetchObject("/projects/$encodedProjectId", type = GitLabProject::class.java)
}
