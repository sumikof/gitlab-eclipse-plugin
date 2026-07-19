package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.inject.service

/** Fetches issues assigned to the authenticated user. */
class IssueService(private val apiClient: GitLabApiClient = service()) {
  fun getIssuesAssignedToMe(): List<GitLabIssue> {
    val request = ApiRequest(
      path = "/issues",
      query = mapOf("scope" to "assigned_to_me", "state" to "opened"),
      elementType = GitLabIssue::class.java,
    )
    return apiClient.fetchListFromApi(request).distinctBy { it.id }
  }
}
