package com.gitlab.eclipse.mergerequests.discussions

import com.gitlab.eclipse.ci.actions.normalizeInstanceUrl

/**
 * Identity of one discussion write target (design §14.4): the normalized instance url, the
 * fingerprint of the acting account, the kind ("discussion" | "note" | "mergeRequest"), and the
 * id of the object being written to. The ACTION is deliberately NOT part of the key, so an edit
 * and a delete on the same note serialize instead of racing. Create operations key on an
 * identifier that exists *before* the request is sent (the MR GID), because no discussion/note
 * id exists yet at that point.
 */
data class DiscussionWriteKey(
  val instanceUrl: String,
  val authFingerprint: String,
  val targetKind: String,
  val targetId: String,
) {
  companion object {
    fun forDiscussion(instanceUrl: String, authFingerprint: String, replyId: String) =
      DiscussionWriteKey(normalizeInstanceUrl(instanceUrl), authFingerprint, "discussion", replyId)

    fun forNote(instanceUrl: String, authFingerprint: String, noteGid: String) =
      DiscussionWriteKey(normalizeInstanceUrl(instanceUrl), authFingerprint, "note", noteGid)

    fun forMergeRequest(instanceUrl: String, authFingerprint: String, mrGid: String) =
      DiscussionWriteKey(normalizeInstanceUrl(instanceUrl), authFingerprint, "mergeRequest", mrGid)
  }
}
