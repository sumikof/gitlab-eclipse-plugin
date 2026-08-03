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
 *
 * [nodeId] is the [com.gitlab.eclipse.views.sidebar.DiscussionsSectionNode.nodeId] of the display
 * node the fetch targets: the same merge request appears under two sidebar sections as two
 * independent nodes, and without a per-node identity in the key the second node's load would
 * supersede the first's and strand it in LOADING forever (its own load reports Superseded and,
 * correctly, touches nothing; the second settles only itself). A re-load of the SAME node still
 * supersedes that node's earlier load, since the id is stable per node instance.
 */
data class DiscussionKey(
  val instanceUrl: String,
  val authFingerprint: String,
  val projectId: Long,
  val mrIid: Long,
  val nodeId: Long,
) {
  companion object {
    /** Normalizes [instanceUrl] via [normalizeInstanceUrl]; the rest is stored verbatim. */
    fun of(instanceUrl: String, authFingerprint: String, projectId: Long, mrIid: Long, nodeId: Long): DiscussionKey =
      DiscussionKey(
        instanceUrl = normalizeInstanceUrl(instanceUrl),
        authFingerprint = authFingerprint,
        projectId = projectId,
        mrIid = mrIid,
        nodeId = nodeId,
      )
  }
}
