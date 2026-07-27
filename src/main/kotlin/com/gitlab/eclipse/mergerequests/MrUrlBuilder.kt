package com.gitlab.eclipse.mergerequests

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Builds GitLab merge-request web URLs (VSCode `gl.showMergeRequests` parity). */
object MrUrlBuilder {
  fun assignedMergeRequestsUrl(projectWebUrl: String, userId: Long): String =
    "${projectWebUrl.trimEnd('/')}/-/merge_requests?assignee_id=$userId"

  fun newMergeRequestUrl(projectWebUrl: String, sourceBranch: String): String =
    "${projectWebUrl.trimEnd('/')}/-/merge_requests/new" +
      "?merge_request%5Bsource_branch%5D=${encodeQueryValue(sourceBranch)}"

  fun compareUrl(projectWebUrl: String, fromRef: String, toRef: String): String =
    "${projectWebUrl.trimEnd('/')}/-/compare/${encodeRefSegment(fromRef)}...${encodeRefSegment(toRef)}"

  /** Form-urlencodes a query-string value (space -> `+`). */
  private fun encodeQueryValue(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

  /** Percent-encodes a value for use as a URL path segment (space -> `%20`, `/` encoded too). */
  private fun encodeRefSegment(ref: String): String =
    URLEncoder.encode(ref, StandardCharsets.UTF_8).replace("+", "%20")
}
