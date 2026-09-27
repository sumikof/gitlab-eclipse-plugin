package com.gitlab.eclipse.mergerequests.review

/**
 * The merge request a review session or a line-comment attempt talks to (design §12.1).
 * [instanceUrl] and [authFingerprint] are the non-secret tags of the connection the MR was found
 * over; every later request and write is gated on them. [mrGid] is built from the MR's global
 * `id` (never its `iid`), and [namespaceWithPath] addresses the discussions query.
 */
data class MergeRequestRef(
  val instanceUrl: String,
  val authFingerprint: String,
  val projectId: Long,
  val mrIid: Long,
  val mrGid: String,
  val namespaceWithPath: String,
)

/**
 * What a document's review session is about (design §9.5). Two sessions are shared only when
 * all six components are equal: another MR, another account, or another MR version (a different
 * [headSha]) is a different session.
 */
data class SessionIdentity(
  val instanceUrl: String,
  val authFingerprint: String,
  val projectId: Long,
  val mrIid: Long,
  val headSha: String,
  val newPath: String,
)

/**
 * One loaded review session (design §12.1): the MR version the editor's file is anchored to,
 * its line map, whether the user may comment, and the threads to annotate. Immutable; a reload
 * produces a new snapshot.
 */
data class ReviewSessionSnapshot(
  val identity: SessionIdentity,
  val mrRef: MergeRequestRef,
  val baseSha: String,
  val startSha: String,
  val headSha: String,
  val oldPath: String,
  val newPath: String,
  val lineMap: DiffLineMap,
  val canCreateNote: Boolean,
  val placements: List<PlacedThread>,
)
