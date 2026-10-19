package com.gitlab.eclipse.mergerequests.diff

import com.gitlab.eclipse.views.sidebar.ChangeType

/** One side of an MR file diff: the repository-relative [path] at commit [sha]. */
data class SideRef(val path: String, val sha: String)

/** Shape of an MR file diff; maps onto Compare's `Differencer` kinds in the adapter. */
enum class MrDiffKind { ADDITION, DELETION, CHANGE }

/**
 * Which file is shown on each side of an MR file diff, and how the editor names it (design §4, §8).
 *
 * The left side is always the base commit and the right side always the head commit: base reads
 * the old path (absent for an added file), head reads the new path (absent for a deleted file), so
 * a rename shows `old_path@base` against `new_path@head`. This is the only place that rule lives.
 */
data class MrDiffSides(
  val left: SideRef?,
  val right: SideRef?,
  val kind: MrDiffKind,
  val title: String,
  val tooltip: String,
  val leftLabel: String,
  val rightLabel: String,
  val submodule: Boolean,
) {
  companion object {
    private const val SHORT_SHA_LENGTH = 8
    private const val GITLINK_MODE = "160000"

    /** Derives the sides of [request]; the request is already validated, so the paths it needs are present. */
    fun of(request: MrFileDiffRequest): MrDiffSides {
      val left = baseSide(request)
      val right = headSide(request)
      val iid = "!${request.mrIid}"
      val shownPath = (right ?: left)?.path.orEmpty()
      val paths = listOfNotNull(left?.path, right?.path).distinct().joinToString(" → ")
      return MrDiffSides(
        left = left,
        right = right,
        kind = kindOf(request.changeType),
        title = "${shownPath.substringAfterLast('/')} ($iid)",
        tooltip = "${request.namespaceWithPath ?: request.projectId} $iid: $paths",
        leftLabel = left?.let(::label) ?: "(added in $iid)",
        rightLabel = right?.let(::label) ?: "(deleted in $iid)",
        submodule = request.aMode == GITLINK_MODE || request.bMode == GITLINK_MODE,
      )
    }

    /** The old path at base; absent for an added file. */
    private fun baseSide(request: MrFileDiffRequest): SideRef? {
      if (request.changeType == ChangeType.NEW) return null
      return (request.oldPath ?: request.newPath)?.let { SideRef(it, request.baseSha) }
    }

    /** The new path at head; absent for a deleted file. */
    private fun headSide(request: MrFileDiffRequest): SideRef? {
      if (request.changeType == ChangeType.DELETED) return null
      return (request.newPath ?: request.oldPath)?.let { SideRef(it, request.headSha) }
    }

    private fun kindOf(changeType: ChangeType): MrDiffKind =
      when (changeType) {
        ChangeType.NEW -> MrDiffKind.ADDITION
        ChangeType.DELETED -> MrDiffKind.DELETION
        ChangeType.MODIFIED, ChangeType.RENAMED -> MrDiffKind.CHANGE
      }

    private fun label(side: SideRef): String = "${side.path} @ ${side.sha.take(SHORT_SHA_LENGTH)}"
  }
}
