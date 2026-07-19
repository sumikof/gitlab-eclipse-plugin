package com.gitlab.eclipse.api

/** Thrown when a GitLab REST call returns a non-2xx status. */
class GitLabApiException(val statusCode: Int, val responseBody: String) :
  RuntimeException("GitLab API request failed with HTTP $statusCode")
