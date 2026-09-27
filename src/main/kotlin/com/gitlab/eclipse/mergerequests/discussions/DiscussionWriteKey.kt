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

    /**
     * A key for the editor line-comment write path (design §9.3.1). Unlike the other factories,
     * this key is NOT scoped by instance/account: it is built entirely from information available
     * in the UI turn that opens the popup (the file path and its 1-based line), before any
     * connection or MR identifier is resolved. `instanceUrl`/`authFingerprint` are therefore `""`
     * — not omitted, so the shape stays a plain [DiscussionWriteKey] — which is enough to
     * serialize concurrent writes to the same file+line without racing.
     */
    fun forEditorLine(filePath: String, oneBasedLine: Int) =
      DiscussionWriteKey("", "", "editorLine", "$filePath:$oneBasedLine")
  }
}
