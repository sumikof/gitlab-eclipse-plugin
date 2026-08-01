package com.gitlab.eclipse.api

/**
 * Thrown when a GitLab REST call returns a non-2xx status. [correlationId] carries the server's
 * `x-request-id` (when present) for audit/troubleshooting correlation; it is null when the header
 * is absent from the response.
 */
class GitLabApiException(val statusCode: Int, val responseBody: String, val correlationId: String? = null) :
  RuntimeException("GitLab API request failed with HTTP $statusCode")
