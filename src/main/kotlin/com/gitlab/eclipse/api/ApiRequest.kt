package com.gitlab.eclipse.api

/**
 * A GitLab REST list request. [elementType] is the JSON array element type; the
 * client fetches every page and returns the aggregated list of elements.
 */
data class ApiRequest<T>(
  val path: String,
  val query: Map<String, String> = emptyMap(),
  val elementType: Class<T>,
)
