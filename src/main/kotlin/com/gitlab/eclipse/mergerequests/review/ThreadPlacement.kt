package com.gitlab.eclipse.mergerequests.review

import com.gitlab.eclipse.api.model.GitLabDiscussion

/**
 * The three merge-request-version commits the current review session is anchored to (design
 * §12.1, §12.3): the version's own `baseSha`/`startSha`/`headSha`, as opposed to
 * [com.gitlab.eclipse.api.model.GitLabDiffRefs], which carries an *existing note's* recorded
 * position.
 */
data class VersionRefs(val baseSha: String, val startSha: String, val headSha: String)

/** A discussion thread selected for display as an editor annotation (design FR-3, §12.1). */
data class PlacedThread(val oneBasedLine: Int, val discussion: GitLabDiscussion, val resolved: Boolean)

/**
 * Selects, from [discussions], the ones to annotate in the editor for [refs] and [newPath]
 * (design FR-3). A discussion is placed only when its FIRST note (the root note; a reply's
 * position is never consulted, matching FR-3) has a position where all of the following hold:
 * `positionType == "text"`, `diffRefs` is present and matches [refs] in all three SHAs, `newPath`
 * matches the editor's file, and `newLine` is non-null. Discussions with no notes, or whose first
 * note has no position at all (a plain discussion note), are skipped. The returned
 * [PlacedThread.resolved] is the discussion's own `resolved` flag, independent of placement
 * (resolved threads are still placed, per FR-3: they are simply a different annotation type).
 */
fun placeThreads(discussions: List<GitLabDiscussion>, refs: VersionRefs, newPath: String): List<PlacedThread> =
  discussions.mapNotNull { discussion ->
    val position = discussion.notes.firstOrNull()?.position ?: return@mapNotNull null
    val diffRefs = position.diffRefs ?: return@mapNotNull null
    val newLine = position.newLine ?: return@mapNotNull null
    val matches = position.positionType == "text" &&
      diffRefs.baseSha == refs.baseSha &&
      diffRefs.startSha == refs.startSha &&
      diffRefs.headSha == refs.headSha &&
      position.newPath == newPath
    if (matches) PlacedThread(newLine, discussion, discussion.resolved) else null
  }
