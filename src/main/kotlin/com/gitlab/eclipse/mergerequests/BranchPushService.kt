package com.gitlab.eclipse.mergerequests

import com.gitlab.eclipse.inject.service
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.StoredConfig
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import java.io.File

/**
 * Outcome of [BranchPushService.push]. Exactly one of these is returned; the service never
 * throws.
 */
sealed interface PushOutcome {
  /** The remote ref was updated (or already at the pushed commit); upstream config was set. */
  data object Ok : PushOutcome

  /**
   * The remote refused the update without throwing — [RemoteRefUpdate.Status] such as
   * `REJECTED_NONFASTFORWARD` or `REJECTED_OTHER_REASON` (e.g. a protected branch). [status]
   * is null when the push produced no [RemoteRefUpdate] for the branch at all. Upstream config
   * is NOT written.
   */
  data class Rejected(val status: RemoteRefUpdate.Status?) : PushOutcome

  /** Any thrown failure (transport, auth, missing repository...). */
  data class Failed(val cause: Throwable?) : PushOutcome

  /** Another guarded git operation is already running on this repository
   *  ([GitOperationGuard.withRepo] rejected the call); nothing was done. */
  data object Busy : PushOutcome
}

/**
 * Pushes a local branch to the resolved GitLab remote so a merge request can be created for it
 * (Phase 3 §8.5). Plain `refs/heads/<branch>:refs/heads/<branch>` refspec — never forced. On
 * success the branch's upstream (`branch.<name>.remote`/`.merge`) is written so subsequent
 * openCreateNewMR invocations take the direct-URL path.
 *
 * Success is gated on the [RemoteRefUpdate.Status] of the pushed ref being `OK` or
 * `UP_TO_DATE` — JGit reports server-side rejections in the [org.eclipse.jgit.transport.PushResult]
 * without throwing, and treating those as success would open a new-MR URL for a branch that does
 * not exist on the server.
 *
 * Never throws — every failure maps to a [PushOutcome]. Blocking git/network I/O: call from a
 * background thread.
 */
class BranchPushService(
  private val guard: GitOperationGuard = service(),
  private val auth: GitAuthConfigurer = GitAuthConfigurer(),
) {

  fun push(context: RepositoryContext, branch: String): PushOutcome =
    try {
      guard.withRepo(context.gitDir) { doPush(context, branch) } ?: PushOutcome.Busy
    } catch (e: Exception) {
      PushOutcome.Failed(e)
    }

  /** Runs inside the guard: open repo → push the one refspec → gate on the ref's status →
   *  set upstream config only on success. */
  private fun doPush(context: RepositoryContext, branch: String): PushOutcome =
    FileRepositoryBuilder().setGitDir(File(context.gitDir)).setMustExist(true).build().use { repo ->
      val remoteUrl = repo.config.getString("remote", context.remoteName, "url").orEmpty()
      val refName = "$REFS_HEADS$branch"
      val push = Git(repo).push()
        .setRemote(context.remoteName)
        .setRefSpecs(RefSpec("$refName:$refName"))
      val results = auth.applyAuth(push, remoteUrl, context.instanceUrl).call()
      // A push can span several transport URIs (one PushResult each); the update for our ref
      // is the first non-null across them.
      val update = results.asSequence().mapNotNull { it.getRemoteUpdate(refName) }.firstOrNull()
      when (update?.status) {
        RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE -> {
          setUpstream(repo.config, branch, context.remoteName, refName)
          PushOutcome.Ok
        }
        else -> PushOutcome.Rejected(update?.status)
      }
    }

  /** Records `branch.<branch>.remote`/`.merge` so the branch now tracks the pushed ref. */
  private fun setUpstream(
    config: StoredConfig,
    branch: String,
    remoteName: String,
    refName: String,
  ) {
    config.setString("branch", branch, "remote", remoteName)
    config.setString("branch", branch, "merge", refName)
    config.save()
  }

  private companion object {
    const val REFS_HEADS = "refs/heads/"
  }
}
