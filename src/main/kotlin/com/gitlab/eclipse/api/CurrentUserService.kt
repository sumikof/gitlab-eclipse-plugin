package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.model.GitLabUser
import com.gitlab.eclipse.inject.service

/** Fetches the authenticated user's profile. */
class CurrentUserService(private val apiClient: GitLabApiClient = service()) {
  /** When [connection] is non-null, `/user` is read through that pinned snapshot. */
  fun getCurrentUser(connection: ConnectionSnapshot? = null): GitLabUser =
    apiClient.fetchObject("/user", type = GitLabUser::class.java, connection = connection)
}
