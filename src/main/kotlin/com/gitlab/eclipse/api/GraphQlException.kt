package com.gitlab.eclipse.api

/**
 * Thrown when a GitLab GraphQL response reports failure: either a top-level `errors` array in the
 * response body, or a mutation payload's own `errors` array being non-empty. [hasDataKey] records
 * whether the `data` key was present in the response object (regardless of whether its value was
 * null) — a later PR uses this to decide whether execution provably never started (`data` absent)
 * versus cannot be proven either way (`data` present). This class only carries the flag; it
 * performs no classification itself. [messages] holds the `message` strings extracted from the
 * GraphQL `errors` array. [correlationId] carries the server's `x-request-id` (when present) for
 * audit/troubleshooting correlation; it is null when the header is absent from the response.
 */
class GraphQlException(
  val hasDataKey: Boolean,
  val messages: List<String>,
  val correlationId: String? = null,
) : RuntimeException(buildMessage(messages))

private fun buildMessage(messages: List<String>): String =
  if (messages.isEmpty()) {
    "GraphQL request failed"
  } else {
    "GraphQL request failed: " + messages.joinToString("; ")
  }
