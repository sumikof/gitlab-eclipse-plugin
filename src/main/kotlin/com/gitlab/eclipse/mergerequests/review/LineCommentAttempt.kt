package com.gitlab.eclipse.mergerequests.review

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.DiscussionService
import com.gitlab.eclipse.api.DiscussionWriteService
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.MergeRequestService
import com.gitlab.eclipse.api.UnstableConnectionException
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.api.model.GitLabMrVersion
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.mergerequests.CurrentBranch
import com.gitlab.eclipse.mergerequests.CurrentBranchGitReader
import com.gitlab.eclipse.mergerequests.CurrentBranchMrLookup
import com.gitlab.eclipse.mergerequests.RepositoryContext
import com.gitlab.eclipse.mergerequests.RepositoryContextResolver
import com.gitlab.eclipse.mergerequests.discussions.DiscussionGenerationRegistry
import com.gitlab.eclipse.mergerequests.discussions.DiscussionWriteOutcome
import com.gitlab.eclipse.mergerequests.discussions.runDiscussionWrite
import kotlinx.coroutines.CancellationException
import org.eclipse.jgit.lib.Repository
import java.io.File
import java.io.IOException
import java.nio.charset.Charset

/**
 * The MR target one launch's attempt resolved (design §9.3.1): written on the background thread
 * once the attempt has cleared G6 (or taken the session's target) and G9, read by the launch's
 * terminal `reload` on the UI thread to establish or refresh the session. `null` until then, and
 * reset to `null` at the start of every attempt, so it always describes the latest attempt.
 */
class AttemptTarget {
  @Volatile var value: Pair<SessionIdentity, MergeRequestRef>? = null
}

/**
 * What the UI turn froze before the popup opened (design §9.3, §9.3.2): the file's absolute
 * [filePath], the 1-based line, the document's line count (`0` for an empty document),
 * its full text, and the editor's charset.
 */
data class LineSnapshot(
  val filePath: File,
  val oneBasedLine: Int,
  val lineCount: Int,
  val documentText: String,
  val charset: Charset,
)

/**
 * One attempt to create a diff thread on an editor line (design §9.3 [BG], §9.3.1). Stateless:
 * `run` is the launcher's `write`, so a `[Retry]` calls it again and every gate is evaluated
 * again (A14). Every effect is injected, with the production services as defaults.
 */
class LineCommentAttempt(
  private val apiClient: GitLabApiClient = service(),
  private val captureConnection: () -> ConnectionSnapshot = { apiClient.captureConnection() },
  private val candidateContexts: () -> List<RepositoryContext> = { RepositoryContextResolver().candidateContexts() },
  private val readBranch: (File) -> CurrentBranch = { CurrentBranchGitReader().read(it) },
  private val lookupMr: (RepositoryContext, CurrentBranch, ConnectionSnapshot) -> GitLabMergeRequest? =
    { context, branch, conn -> CurrentBranchMrLookup().lookup(context, branch, conn).mr },
  private val canCreateNote: (ConnectionSnapshot, String, Long) -> Boolean = ::fetchCanCreateNote,
  private val getLatestMrVersion: (String, Long, ConnectionSnapshot) -> GitLabMrVersion? =
    { projectId, iid, conn -> service<MergeRequestService>().getLatestMrVersion(projectId, iid, conn) },
  private val openRepository: (File) -> Repository = ::openGitDir,
  private val checkBody: (Repository, String, File, String, Charset) -> BodyIdentity = ::checkBodyIdentity,
  private val blobsFor: (Repository) -> DiffBlobs = ::RepositoryDiffBlobs,
  private val createDiffNote: (ConnectionSnapshot, String, String, Map<String, Any?>) -> Unit =
    { conn, mrGid, body, position -> service<DiscussionWriteService>().createDiffNote(conn, mrGid, body, position) },
  private val isActive: () -> Boolean = { true },
  private val registryActive: () -> Boolean = { DiscussionGenerationRegistry.active },
  private val registryEpoch: () -> Long = { DiscussionGenerationRegistry.currentEpoch },
) {
  /**
   * Background only. In this order, and in full on every call:
   *
   * 1. capture the connection once (unstable → [DiscussionWriteOutcome.GateRejected]); with a
   *    [session], it must be the session's instance and account (else GateRejected);
   * 2. G5 the workspace repository containing the file (the innermost one);
   * 3. G6 the MR: the [session]'s, else the current branch's open MR;
   * 4. G6b `createNote` permission, G7 HEAD == the MR's head, G8 [checkBodyIdentity];
   * 5. G9 the latest version (same head, an entry for the file) and the position from [buildPosition];
   * 6. record the target in [target], then [runDiscussionWrite]: the pinned-connection gate
   *    (GateRejected), the lifecycle re-check against [startEpoch] (Aborted), `createDiffNote`,
   *    and [com.gitlab.eclipse.mergerequests.discussions.classifyWriteFailure] for a send failure.
   *
   * A gate that does not hold returns [DiscussionWriteOutcome.Rejected] with a fixed message and
   * sends nothing. A failure before the send (a request or JGit error) is
   * [DiscussionWriteOutcome.Definite]: nothing was sent, so a retry is safe and re-runs the gates.
   * Cancellation is rethrown. Nothing is logged here.
   */
  fun run(
    snapshot: LineSnapshot,
    session: ReviewSessionSnapshot?,
    target: AttemptTarget,
    body: String,
    startEpoch: Long,
  ): DiscussionWriteOutcome {
    target.value = null
    val cleared = try {
      val connection = captureConnection()
      val tags = session?.identity
      if (tags != null && !connectionMatches(tags.instanceUrl, tags.authFingerprint, connection)) {
        return DiscussionWriteOutcome.GateRejected
      }
      clearGates(snapshot, session, connection)
    } catch (_: UnstableConnectionException) {
      return DiscussionWriteOutcome.GateRejected
    } catch (refusal: GateRefusal) {
      return DiscussionWriteOutcome.Rejected(refusal.userMessage)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return DiscussionWriteOutcome.Definite(e)
    }
    target.value = cleared.identity to cleared.ref
    return runDiscussionWrite(
      apiClient,
      cleared.identity.instanceUrl,
      cleared.identity.authFingerprint,
      startEpoch,
      isActive,
      registryActive,
      registryEpoch,
    ) { pinned -> createDiffNote(pinned, cleared.ref.mrGid, body, cleared.position) }
  }

  /** Everything [run] needs to send, once G5-G9 all hold. */
  private class Cleared(val identity: SessionIdentity, val ref: MergeRequestRef, val position: Map<String, Any?>)

  /** The MR to comment on and the head sha the checkout must be at (G6). */
  private class MrTarget(val ref: MergeRequestRef, val headSha: String)

  /** Throws [GateRefusal] for the first gate that does not hold. */
  private fun clearGates(
    snapshot: LineSnapshot,
    session: ReviewSessionSnapshot?,
    connection: ConnectionSnapshot,
  ): Cleared {
    val (context, relPath) = locateRepository(snapshot.filePath) ?: refuse(NO_REPOSITORY_MESSAGE)
    if (session != null && relPath != session.identity.newPath) refuse(FILE_MISMATCH_MESSAGE)
    val branch = readBranch(File(context.gitDir))
    val mr = session?.let { MrTarget(it.mrRef, it.identity.headSha) } ?: currentBranchMr(context, branch, connection)
    if (!canCreateNote(connection, mr.ref.namespaceWithPath, mr.ref.mrIid)) refuse(NO_PERMISSION_MESSAGE)
    if (branch.headSha == null || branch.headSha != mr.headSha) refuse(CHECKOUT_FIRST_MESSAGE)
    return openRepository(File(context.gitDir)).use { repository ->
      val identity = checkBody(repository, relPath, snapshot.filePath, snapshot.documentText, snapshot.charset)
      if (identity is BodyIdentity.Different) refuse(FILE_MISMATCH_MESSAGE)
      val (diff, position) = versionGate(mr, relPath, snapshot, connection, blobsFor(repository))
      val sessionIdentity = session?.identity ?: SessionIdentity(
        instanceUrl = mr.ref.instanceUrl,
        authFingerprint = mr.ref.authFingerprint,
        projectId = mr.ref.projectId,
        mrIid = mr.ref.mrIid,
        headSha = diff.refs.headSha,
        newPath = relPath,
      )
      Cleared(sessionIdentity, mr.ref, position)
    }
  }

  /** G9: the latest version still has [MrTarget.headSha] and an entry for [relPath]; freezes the position. */
  private fun versionGate(
    mr: MrTarget,
    relPath: String,
    snapshot: LineSnapshot,
    connection: ConnectionSnapshot,
    blobs: DiffBlobs,
  ): Pair<VersionDiff.Found, Map<String, Any?>> {
    val version = getLatestMrVersion(mr.ref.projectId.toString(), mr.ref.mrIid, connection)
    val diff = when (val resolved = resolveVersionDiff(version, mr.headSha, relPath, blobs)) {
      is VersionDiff.Missing -> refuse(resolved.message)
      is VersionDiff.Found -> resolved
    }
    val built = buildPosition(
      refs = diff.refs,
      oldPath = diff.oldPath,
      newPath = diff.newPath,
      map = diff.lineMap,
      oneBasedLine = snapshot.oneBasedLine,
      lineCount = snapshot.lineCount,
    )
    return when (built) {
      is PositionResult.Refused -> refuse(built.reason.message())
      is PositionResult.Ready -> diff to built.variables
    }
  }

  /**
   * G5: the workspace repository whose work tree contains [file], and the file's path in it. With
   * nested repositories the innermost one owns the file, as in Git.
   */
  private fun locateRepository(file: File): Pair<RepositoryContext, String>? =
    candidateContexts()
      .mapNotNull { context -> repositoryRelativePath(File(context.workTree), file)?.let { context to it } }
      .minByOrNull { (_, relPath) -> relPath.length }

  /** G6 without a session: the current branch's open MR over [connection]. */
  private fun currentBranchMr(
    context: RepositoryContext,
    branch: CurrentBranch,
    connection: ConnectionSnapshot,
  ): MrTarget {
    val mr = lookupMr(context, branch, connection) ?: refuse(NO_MERGE_REQUEST_MESSAGE)
    val references = mr.references?.full ?: refuse(NO_MERGE_REQUEST_MESSAGE)
    val headSha = mr.sha ?: refuse(NO_MERGE_REQUEST_MESSAGE)
    val ref = MergeRequestRef(
      instanceUrl = connection.instanceUrl,
      authFingerprint = connection.authFingerprint,
      projectId = mr.projectId,
      mrIid = mr.iid,
      mrGid = DiscussionService.mrGid(mr.id),
      namespaceWithPath = DiscussionService.namespaceWithPath(references),
    )
    return MrTarget(ref, headSha)
  }

  /** A gate that does not hold; [userMessage] is one of the fixed messages below. */
  private class GateRefusal(val userMessage: String) : RuntimeException(userMessage)

  private fun refuse(message: String): Nothing = throw GateRefusal(message)

  private fun RefuseReason.message(): String = when (this) {
    RefuseReason.DIFF_UNAVAILABLE -> DIFF_UNAVAILABLE_MESSAGE
    RefuseReason.LINE_OUT_OF_RANGE -> LINE_OUT_OF_RANGE_MESSAGE
  }

  /** Fixed, server-free texts for [DiscussionWriteOutcome.Rejected] (design §14, §19). */
  companion object {
    const val SAVE_FIRST_MESSAGE = "Save the file before commenting on a line."
    const val NO_REPOSITORY_MESSAGE = "This file is not in a GitLab repository of the workspace."
    const val NO_MERGE_REQUEST_MESSAGE = "No open merge request was found for the current branch."
    const val NO_PERMISSION_MESSAGE = "You do not have permission to comment on this merge request."

    /** Same wording as the sidebar's open-file command (`OpenMrFileHandler`). */
    const val CHECKOUT_FIRST_MESSAGE =
      "Check out the merge request branch first (the working tree does not match this merge request)."
    const val FILE_MISMATCH_MESSAGE = "This file does not match the merge request revision; its line numbers would not line up."
    const val DIFF_UNAVAILABLE_MESSAGE = "This file's diff is too large to comment on from the editor."
    const val LINE_OUT_OF_RANGE_MESSAGE = "The selected line is not in the file."
  }
}

/**
 * [file]'s path relative to [workTree] with `/` separators, or `null` when it is not inside it.
 * Directories are resolved canonically (so a symlinked home directory still matches) but the file
 * name itself is not: a symlinked file keeps its own path, which G8 then refuses (design §9.3.2
 * G8a'').
 */
internal fun repositoryRelativePath(workTree: File, file: File): String? = try {
  val root = workTree.canonicalFile.toPath()
  val parent = file.absoluteFile.parentFile?.canonicalFile?.toPath()
  if (parent == null || !parent.startsWith(root)) {
    null
  } else {
    root.relativize(parent.resolve(file.name)).joinToString("/").takeIf { it.isNotEmpty() }
  }
} catch (_: IOException) {
  null
}

/**
 * G6b's production check: `mergeRequest.userPermissions.createNote` from the discussions query's
 * first page (design §9.3). Runs the full discussions fetch; only its permission is used.
 */
private fun fetchCanCreateNote(connection: ConnectionSnapshot, namespaceWithPath: String, mrIid: Long): Boolean =
  service<DiscussionService>()
    .getDiscussions(connection, namespaceWithPath, mrIid, DiscussionService.DISCUSSIONS_DEADLINE)
    .canCreateNote
