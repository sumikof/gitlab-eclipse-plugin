package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.inject.service

/** Fetches merge requests assigned to the authenticated user. */
class MergeRequestService(private val apiClient: GitLabApiClient = service()) {
  fun getMergeRequestsAssignedToMe(): List<GitLabMergeRequest> {
    val request = ApiRequest(
      path = "/merge_requests",
      query = mapOf("scope" to "assigned_to_me", "state" to "opened"),
      elementType = GitLabMergeRequest::class.java,
    )
    return apiClient.fetchListFromApi(request).distinctBy { it.id }
  }
}
