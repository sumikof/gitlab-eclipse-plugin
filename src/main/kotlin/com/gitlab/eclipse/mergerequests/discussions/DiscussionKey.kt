package com.gitlab.eclipse.mergerequests.discussions

import com.gitlab.eclipse.ci.actions.normalizeInstanceUrl

/**
 * In-flight identity of one discussion fetch. Mirrors [com.gitlab.eclipse.ci.joblog.JobLogKey]:
 * the key folds in [authFingerprint] alongside the normalized [instanceUrl] so that switching
 * accounts against the same instance does not let a slow in-flight fetch for the old account
 * compete for the same generation slot as the new account's fetch (which could otherwise land
 * the old account's data in the tree). [authFingerprint] is a non-reversible, non-secret digest
 * of the credential (see [com.gitlab.eclipse.api.ConnectionSnapshot]), so holding it here is
 * safe -- but it must never be logged.
 */
data class DiscussionKey(
  val instanceUrl: String,
  val authFingerprint: String,
  val projectId: Long,
  val mrIid: Long,
) {
  companion object {
    /** Normalizes [instanceUrl] via [normalizeInstanceUrl]; the rest is stored verbatim. */
    fun of(instanceUrl: String, authFingerprint: String, projectId: Long, mrIid: Long): DiscussionKey =
      DiscussionKey(
        instanceUrl = normalizeInstanceUrl(instanceUrl),
        authFingerprint = authFingerprint,
        projectId = projectId,
        mrIid = mrIid,
      )
  }
}
