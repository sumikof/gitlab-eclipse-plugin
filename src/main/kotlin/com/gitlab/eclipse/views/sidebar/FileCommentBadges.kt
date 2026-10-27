package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.DiscussionsReadResult
import com.gitlab.eclipse.api.model.GitLabDiscussion

/**
 * The paths that carry a text-diff thread on the current MR version, per diff side (VS Code
 * `uriForDiscussion` parity): [oldPaths] on the base side, [newPaths] on the head side.
 */
internal data class CommentedSides(val oldPaths: Set<String>, val newPaths: Set<String>)

/** What is known about the comments on one MR's changed files. Replaced as a whole; never mutated. */
internal sealed interface CommentIndex {
  /** Nothing fetched yet (first fetch not started or still running). */
  object Pending : CommentIndex

  /** Every discussion was fetched: a file not in [sides] has no comment. */
  data class Complete(val sides: CommentedSides) : CommentIndex

  /** The fetch was truncated: a file in [sides] has a comment, any other file is unknown. */
  data class Partial(val sides: CommentedSides) : CommentIndex

  /** The fetch failed, was refused by the connection gate, or the version SHAs are unknown. */
  object Unavailable : CommentIndex
}

/** Whether one changed file has a comment. [ABSENT] only ever comes from a [CommentIndex.Complete]. */
enum class FileCommentState { PRESENT, ABSENT, UNKNOWN }

/**
 * The comment index of ONE built set of MR children, shared by its [DiscussionsSectionNode] and
 * every [ChangedFileNode] built with it. [baseSha] / [headSha] are the diff version's base and head
 * commits the files were listed from. UI thread only: [index] is written by the view when the
 * section's fetch settles and read by the label provider, the same discipline as the section's
 * other mutable state, so it needs no `@Volatile` or lock.
 */
class MrCommentBadges(val baseSha: String?, val headSha: String?) {
  internal var index: CommentIndex = CommentIndex.Pending
}

/**
 * The sides of [discussions] anchored on the version ([baseSha], [headSha]). Only each thread's
 * first (non-system) note counts, and only a `text` position with diff refs: an old-side position
 * (`oldLine != null`, which includes an unchanged line) is matched by `oldPath` against the base
 * SHA, any other by `newPath` against the head SHA. Resolution is ignored, SHAs compare exactly.
 */
internal fun commentedSides(discussions: List<GitLabDiscussion>, baseSha: String, headSha: String): CommentedSides {
  val oldPaths = mutableSetOf<String>()
  val newPaths = mutableSetOf<String>()
  for (discussion in discussions) {
    val position = discussion.notes.firstOrNull { !it.system }?.position ?: continue
    val refs = position.diffRefs
    if (position.positionType != "text" || refs == null) continue
    if (position.oldLine != null) {
      val path = position.oldPath
      if (path != null && refs.baseSha == baseSha) oldPaths += path
    } else {
      val path = position.newPath
      if (path != null && refs.headSha == headSha) newPaths += path
    }
  }
  return CommentedSides(oldPaths, newPaths)
}

/**
 * The index for a settled fetch: [result] is `null` for a failed or gate-rejected fetch. Without
 * both version SHAs nothing can be matched, so that is never reported as "no comments" either.
 */
internal fun commentIndexOf(result: DiscussionsReadResult?, baseSha: String?, headSha: String?): CommentIndex {
  if (result == null || baseSha == null || headSha == null) return CommentIndex.Unavailable
  val sides = commentedSides(result.discussions, baseSha, headSha)
  return if (result.truncation != null) CommentIndex.Partial(sides) else CommentIndex.Complete(sides)
}

/**
 * The comment state of one changed file. A new file has no base side and a deleted file no head
 * side, so those sides never match (the `exists` flag of VS Code's review URIs).
 */
internal fun fileCommentState(
  oldPath: String?,
  newPath: String?,
  changeType: ChangeType,
  index: CommentIndex,
): FileCommentState {
  val sides = when (index) {
    is CommentIndex.Complete -> index.sides
    is CommentIndex.Partial -> index.sides
    CommentIndex.Pending, CommentIndex.Unavailable -> return FileCommentState.UNKNOWN
  }
  val hit = (changeType != ChangeType.NEW && oldPath != null && oldPath in sides.oldPaths) ||
    (changeType != ChangeType.DELETED && newPath != null && newPath in sides.newPaths)
  return when {
    hit -> FileCommentState.PRESENT
    index is CommentIndex.Complete -> FileCommentState.ABSENT
    else -> FileCommentState.UNKNOWN
  }
}
