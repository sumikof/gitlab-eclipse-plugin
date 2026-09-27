package com.gitlab.eclipse.mergerequests.review

import com.gitlab.eclipse.api.model.GitLabMrVersion

/**
 * Classifies one file's diff [entry] into a [DiffLineMap], resolving the empty-`diff` cases the
 * design calls out separately from unified-diff parsing (design §12.2.1), checked in this exact
 * order:
 *
 * 1. `tooLarge == true` or `collapsed == true`: [DiffLineMap.Unavailable] (huge or generated
 *    file). Checked first, regardless of whether `diff` happens to be non-empty.
 * 2. `diff == ""` and `renamedFile`, and [blobIdsEqual] confirms the base `oldPath` blob and HEAD
 *    `newPath` blob are identical (`true`): [DiffLineMap.Identity] (a pure rename; every line maps
 *    1:1 to the same old-file line). [blobIdsEqual] is invoked lazily and only for this row, since
 *    it may perform local JGit I/O; `null` (the base commit isn't available locally, so identity
 *    can't be confirmed) falls through to the safe default below rather than being treated as a
 *    rename.
 * 3. `diff == ""` and `newFile` and [headBlobIsEmpty]: [DiffLineMap.Parsed] with no hunks (a
 *    genuinely empty new file has no lines at all).
 * 4. Any other `diff == ""` (a large file on GitLab < 18.4 without the `tooLarge` flag, a rename
 *    whose base commit isn't available locally, etc.): [DiffLineMap.Unavailable] (safe default).
 * 5. Otherwise `diff` is non-null and non-empty: [DiffLineMap.parse]. A `null` `diff` (the field
 *    was absent from the response) also falls here and is treated the same as case 4, since there
 *    is no diff text to parse.
 */
fun classifyDiff(
  entry: GitLabMrVersion.Diff,
  headBlobIsEmpty: Boolean,
  blobIdsEqual: () -> Boolean?,
): DiffLineMap {
  if (entry.tooLarge == true || entry.collapsed == true) return DiffLineMap.Unavailable

  val diff = entry.diff
  return when {
    diff == "" && entry.renamedFile && blobIdsEqual() == true -> DiffLineMap.Identity
    diff == "" && entry.newFile && headBlobIsEmpty -> DiffLineMap.Parsed(emptyList())
    diff == "" -> DiffLineMap.Unavailable
    diff != null -> DiffLineMap.parse(diff)
    else -> DiffLineMap.Unavailable
  }
}
