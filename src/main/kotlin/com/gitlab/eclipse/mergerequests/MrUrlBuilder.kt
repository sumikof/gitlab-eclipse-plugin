package com.gitlab.eclipse.mergerequests

/** Builds GitLab merge-request web URLs (VSCode `gl.showMergeRequests` parity). */
object MrUrlBuilder {
  fun assignedMergeRequestsUrl(projectWebUrl: String, userId: Long): String =
    "${projectWebUrl.trimEnd('/')}/-/merge_requests?assignee_id=$userId"
}
