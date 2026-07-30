package com.gitlab.eclipse.api

/** Classifies CI-related HTTP failures. */
object CiHttpStatus {
  private const val HTTP_FORBIDDEN = 403
  private const val HTTP_NOT_FOUND = 404
  private val ACCESS_DENIED_STATUS_CODES = setOf(HTTP_FORBIDDEN, HTTP_NOT_FOUND)

  /**
   * Returns `true` if [e] represents a GitLab REST access-denied response (403 forbidden
   * or 404 not found — GitLab returns 404 rather than 403 for resources the caller lacks
   * permission to see, to avoid leaking existence).
   */
  fun isAccessDenied(e: Throwable): Boolean {
    return e is GitLabApiException && e.statusCode in ACCESS_DENIED_STATUS_CODES
  }
}
