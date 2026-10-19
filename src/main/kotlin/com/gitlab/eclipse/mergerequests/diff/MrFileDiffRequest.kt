package com.gitlab.eclipse.mergerequests.diff

import com.gitlab.eclipse.views.sidebar.ChangeType

/**
 * Per-MR-version facts stamped on every changed-file node of that version (REVIEW-01 design §5.2).
 *
 * The connection tags ([instanceUrl], [authFingerprint]) and the numeric [projectId] / [mrIid] come
 * straight from the sidebar fetch, not from the discussions section, so a merge request without
 * `references.full` can still open a diff; [namespaceWithPath] is display-only. The SHAs are the
 * version's, unvalidated: [MrFileDiffRequest.from] is the trust boundary.
 */
data class MrDiffSource(
  val instanceUrl: String,
  val authFingerprint: String,
  val projectId: Long,
  val mrIid: Long,
  val namespaceWithPath: String?,
  val baseSha: String?,
  val startSha: String?,
  val headSha: String?,
)

/**
 * One validated request to compare a changed file between an MR version's base and head (design §6).
 *
 * Built only through [from], which checks every externally-sourced value; an instance therefore
 * always carries lowercase 40/64-hex SHAs and safe repository-relative paths for the sides its
 * [changeType] needs.
 */
data class MrFileDiffRequest(
  val instanceUrl: String,
  val authFingerprint: String,
  val projectId: Long,
  val mrIid: Long,
  val namespaceWithPath: String?,
  val baseSha: String,
  val headSha: String,
  val oldPath: String?,
  val newPath: String?,
  val changeType: ChangeType,
  val aMode: String?,
  val bMode: String?,
) {
  /** Editor identity and dedupe unit: the file at one snapshot. */
  val key: MrFileDiffKey
    get() = MrFileDiffKey(instanceUrl, projectId, mrIid, baseSha, headSha, oldPath, newPath)

  /** Latest-wins unit: the file regardless of snapshot. */
  val fileIdentity: MrFileIdentity
    get() = MrFileIdentity(instanceUrl, projectId, mrIid, oldPath, newPath)

  /** Outcome of [from]. */
  sealed interface Validated {
    data class Ok(val request: MrFileDiffRequest) : Validated

    data class Invalid(val reason: InvalidReason) : Validated
  }

  /** Why [from] refused; a fixed code, safe to log. */
  enum class InvalidReason { NO_SOURCE, BAD_PROJECT, BAD_IID, BAD_SHA, BAD_PATH }

  companion object {
    private const val MAX_PATH_LENGTH = 4096
    private val SHA_PATTERN = Regex("^(?:[0-9a-f]{40}|[0-9a-f]{64})$")

    /**
     * Validates [source] and the node's paths (design §6). Only the paths the change type reads are
     * checked: NEW needs [newPath], DELETED needs [oldPath], everything else needs both.
     */
    fun from(
      source: MrDiffSource?,
      oldPath: String?,
      newPath: String?,
      changeType: ChangeType,
      aMode: String?,
      bMode: String?,
    ): Validated {
      val reason = invalidReason(source, oldPath, newPath, changeType)
      if (reason != null) return Validated.Invalid(reason)
      val valid = checkNotNull(source)
      return Validated.Ok(
        MrFileDiffRequest(
          instanceUrl = valid.instanceUrl,
          authFingerprint = valid.authFingerprint,
          projectId = valid.projectId,
          mrIid = valid.mrIid,
          namespaceWithPath = valid.namespaceWithPath,
          baseSha = checkNotNull(valid.baseSha),
          headSha = checkNotNull(valid.headSha),
          oldPath = oldPath,
          newPath = newPath,
          changeType = changeType,
          aMode = aMode,
          bMode = bMode,
        ),
      )
    }

    private fun invalidReason(
      source: MrDiffSource?,
      oldPath: String?,
      newPath: String?,
      changeType: ChangeType,
    ): InvalidReason? {
      val needsOld = changeType != ChangeType.NEW
      val needsNew = changeType != ChangeType.DELETED
      return when {
        source == null -> InvalidReason.NO_SOURCE
        source.projectId <= 0L -> InvalidReason.BAD_PROJECT
        source.mrIid <= 0L -> InvalidReason.BAD_IID
        !isSha(source.baseSha) || !isSha(source.headSha) -> InvalidReason.BAD_SHA
        needsOld && !isSafePath(oldPath) -> InvalidReason.BAD_PATH
        needsNew && !isSafePath(newPath) -> InvalidReason.BAD_PATH
        else -> null
      }
    }

    private fun isSha(sha: String?): Boolean = sha != null && SHA_PATTERN.matches(sha)

    private fun isSafePath(path: String?): Boolean =
      path != null &&
        path.isNotEmpty() &&
        path.length <= MAX_PATH_LENGTH &&
        path.none { it == '\u0000' || it == '\r' || it == '\n' } &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." }
  }
}

/** Identity of a changed file at one MR snapshot (design §5.1); the auth fingerprint is deliberately absent. */
data class MrFileDiffKey(
  val instanceUrl: String,
  val projectId: Long,
  val mrIid: Long,
  val baseSha: String,
  val headSha: String,
  val oldPath: String?,
  val newPath: String?,
)

/** Identity of a changed file independent of the snapshot: the latest-wins unit (design §5.1). */
data class MrFileIdentity(
  val instanceUrl: String,
  val projectId: Long,
  val mrIid: Long,
  val oldPath: String?,
  val newPath: String?,
)
