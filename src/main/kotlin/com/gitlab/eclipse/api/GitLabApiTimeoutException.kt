package com.gitlab.eclipse.api

/**
 * Thrown by [GitLabApiClient.fetchListWithinDeadline] when the caller-supplied deadline is
 * exceeded between pages. Distinct from [GitLabApiException] (an HTTP error) so callers can
 * treat a paging timeout as its own failure mode rather than an API error.
 */
class GitLabApiTimeoutException(val page: Int) :
  RuntimeException("GitLab API pagination timed out before fetching page $page")
