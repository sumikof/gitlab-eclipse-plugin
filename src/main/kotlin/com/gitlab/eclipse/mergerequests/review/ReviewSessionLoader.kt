package com.gitlab.eclipse.mergerequests.review

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.DiscussionService
import com.gitlab.eclipse.api.DiscussionsReadResult
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.MergeRequestService
import com.gitlab.eclipse.api.UnstableConnectionException
import com.gitlab.eclipse.api.model.GitLabMrVersion
import com.gitlab.eclipse.ci.actions.sameConfiguredInstance
import com.gitlab.eclipse.inject.service
import kotlinx.coroutines.CancellationException
import java.time.Duration

/** Outcome of [ReviewSessionLoader.load]. */
sealed interface LoadResult {
  /** [complete] is `false` when the discussions fetch was truncated (design §9.1.1). */
  data class Loaded(val snapshot: ReviewSessionSnapshot, val complete: Boolean) : LoadResult

  /**
   * No session: [reason] is a fixed, user-facing English sentence with no server data in it.
   * [cause] is set only for an unexpected failure (the reason then points at the Error Log): the
   * caller logs it the secret-safe way — the exception's class name and vetted metadata, never
   * `cause.message` or the exception object, which can carry server text. Expected refusals have
   * no cause.
   */
  data class Refused(val reason: String, val cause: Throwable? = null) : LoadResult {
    /** Keeps [cause]'s message out of any `toString()` (design §19). */
    override fun toString(): String = "LoadResult.Refused(reason=$reason, causeType=${cause?.javaClass?.name})"
  }
}

/**
 * The background part of establishing (or reloading) a review session (design §9.1 [BG]). Holds no
 * state: every effect is injected, with the production services as defaults.
 */
class ReviewSessionLoader(
  private val captureConnection: () -> ConnectionSnapshot = { service<GitLabApiClient>().captureConnection() },
  private val getLatestMrVersion: (String, Long, ConnectionSnapshot) -> GitLabMrVersion? =
    { projectId, iid, conn -> service<MergeRequestService>().getLatestMrVersion(projectId, iid, conn) },
  private val getDiscussions: (ConnectionSnapshot, String, Long, Duration) -> DiscussionsReadResult =
    { conn, ns, iid, budget -> service<DiscussionService>().getDiscussions(conn, ns, iid, budget) },
  private val blobs: DiffBlobs = WorkspaceDiffBlobs(),
  private val deadline: Duration = DiscussionService.DISCUSSIONS_DEADLINE,
) {
  /**
   * Background only. Loads the session for [relPath] (the file's repository-relative path, the
   * diff entry's `new_path`) in the MR [ref], anchored at [expectedHeadSha] (design §9.1):
   *
   * 1. the connection: [conn] when given, otherwise captured exactly once; every request below uses
   *    that one snapshot. It must still be [ref]'s instance and account;
   * 2. the latest MR version, whose head must be [expectedHeadSha] (not pushed since) and which
   *    must have a diff entry whose new path is [relPath];
   * 3. the discussions, placed on the version's refs.
   *
   * Any refusal or failure is [LoadResult.Refused] with a fixed message; an unstable connection
   * (settings mid-change) is an expected refusal without a cause, and any other exception is
   * returned as [LoadResult.Refused.cause] for the caller to log. Nothing is logged here.
   * The snapshot's identity is built from [ref], [expectedHeadSha] and [relPath], so it equals the
   * identity the session was begun with.
   */
  fun load(
    ref: MergeRequestRef,
    relPath: String,
    expectedHeadSha: String,
    conn: ConnectionSnapshot?,
  ): LoadResult = try {
    val connection = conn ?: captureConnection()
    if (!connectionMatches(ref.instanceUrl, ref.authFingerprint, connection)) {
      LoadResult.Refused(CONNECTION_CHANGED_MESSAGE)
    } else {
      val version = getLatestMrVersion(ref.projectId.toString(), ref.mrIid, connection)
      when (val diff = resolveVersionDiff(version, expectedHeadSha, relPath, blobs)) {
        is VersionDiff.Missing -> LoadResult.Refused(diff.message)
        is VersionDiff.Found -> loaded(ref, relPath, expectedHeadSha, diff, connection)
      }
    }
  } catch (e: CancellationException) {
    throw e
  } catch (_: UnstableConnectionException) {
    LoadResult.Refused(CONNECTION_UNSTABLE_MESSAGE)
  } catch (e: Exception) {
    LoadResult.Refused(LOAD_FAILED_MESSAGE, e)
  }

  private fun loaded(
    ref: MergeRequestRef,
    relPath: String,
    expectedHeadSha: String,
    diff: VersionDiff.Found,
    connection: ConnectionSnapshot,
  ): LoadResult.Loaded {
    val result = getDiscussions(connection, ref.namespaceWithPath, ref.mrIid, deadline)
    val snapshot = ReviewSessionSnapshot(
      identity = SessionIdentity(
        instanceUrl = ref.instanceUrl,
        authFingerprint = ref.authFingerprint,
        projectId = ref.projectId,
        mrIid = ref.mrIid,
        headSha = expectedHeadSha,
        newPath = relPath,
      ),
      mrRef = ref,
      baseSha = diff.refs.baseSha,
      startSha = diff.refs.startSha,
      headSha = diff.refs.headSha,
      oldPath = diff.oldPath,
      newPath = diff.newPath,
      lineMap = diff.lineMap,
      canCreateNote = result.canCreateNote,
      placements = placeThreads(result.discussions, diff.refs, diff.newPath),
    )
    return LoadResult.Loaded(snapshot, complete = result.truncation == null)
  }

  companion object {
    const val CONNECTION_CHANGED_MESSAGE =
      "The GitLab connection changed since this merge request was loaded; refresh the sidebar and open the file again."
    const val CONNECTION_UNSTABLE_MESSAGE =
      "The GitLab connection settings were changing; open the file again to load its merge request threads."
    const val LOAD_FAILED_MESSAGE = "Could not load this file's merge request threads — see the Error Log."
  }
}

/** Whether [connection] is still the instance ([instanceUrl], trailing slash ignored) and account ([authFingerprint]). */
internal fun connectionMatches(
  instanceUrl: String,
  authFingerprint: String,
  connection: ConnectionSnapshot,
): Boolean =
  sameConfiguredInstance(instanceUrl, connection.instanceUrl) && authFingerprint == connection.authFingerprint

/** The MR version facts a session and a line comment are anchored to (design §9.1, G9). */
internal sealed interface VersionDiff {
  data class Found(
    val refs: VersionRefs,
    val oldPath: String,
    val newPath: String,
    val lineMap: DiffLineMap,
  ) : VersionDiff

  /** Why there is nothing to anchor to; [message] is fixed and server-free. */
  enum class Missing(val message: String) : VersionDiff {
    NO_VERSION("GitLab returned no usable version of this merge request."),
    HEAD_MOVED("The merge request has changed since this file was checked; its line numbers would not line up."),
    NO_ENTRY("This file is not changed in the merge request."),
  }
}

/**
 * Anchors [newPath] to [version] (design §9.1, G9): the version must carry all three SHAs, its
 * head must be [expectedHeadSha], and one of its diff entries must have [newPath] as its new path.
 * That entry's line map comes from [classifyDiff], with [blobs] consulted only for an empty diff.
 */
internal fun resolveVersionDiff(
  version: GitLabMrVersion?,
  expectedHeadSha: String,
  newPath: String,
  blobs: DiffBlobs,
): VersionDiff {
  val head = version?.headCommitSha
  val base = version?.baseCommitSha
  val start = version?.startCommitSha
  if (head == null || base == null || start == null) return VersionDiff.Missing.NO_VERSION
  if (head != expectedHeadSha) return VersionDiff.Missing.HEAD_MOVED
  val entry = version.diffs.firstOrNull { it.newPath == newPath } ?: return VersionDiff.Missing.NO_ENTRY
  val oldPath = entry.oldPath ?: newPath
  val emptyNewFile = entry.diff == "" && entry.newFile
  val lineMap = classifyDiff(
    entry,
    headBlobIsEmpty = emptyNewFile && blobs.headBlobIsEmpty(head, newPath),
    blobIdsEqual = { blobs.blobIdsEqual(base, oldPath, head, newPath) },
  )
  return VersionDiff.Found(VersionRefs(base, start, head), oldPath, newPath, lineMap)
}
