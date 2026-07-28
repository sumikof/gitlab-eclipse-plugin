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

  /** Runs inside the guard: open repo → push the one refspec → gate on EVERY destination's ref
   *  status → set upstream config only when all destinations succeed. */
  private fun doPush(context: RepositoryContext, branch: String): PushOutcome =
    FileRepositoryBuilder().setGitDir(File(context.gitDir)).setMustExist(true).build().use { repo ->
      // Credentials are scoped per transport by GitAuthConfigurer: JGit opens one transport per
      // push URI (`remote.<name>.pushurl` when set, else `url`), and each is handed the token only
      // if its own URI is the GitLab instance over HTTPS — so a second pushurl on another host is
      // never handed the token and a GitLab pushurl always authenticates, regardless of order.
      val refName = "$REFS_HEADS$branch"
      val push = Git(repo).push()
        .setRemote(context.remoteName)
        .setRefSpecs(RefSpec("$refName:$refName"))
      val results = auth.applyAuth(push, context.instanceUrl).call()
      // A push can span several transport URIs (one PushResult each). Success requires the ref
      // update to be OK/UP_TO_DATE at EVERY destination: one accepting URI must not mask a
      // rejection from another (e.g. a protected mirror), which would wrongly open the MR page.
      val updates = results.mapNotNull { it.getRemoteUpdate(refName) }
      val rejected = updates.firstOrNull { !it.status.isAccepted() }
      when {
        updates.isEmpty() -> PushOutcome.Rejected(null)
        rejected != null -> PushOutcome.Rejected(rejected.status)
        else -> {
          setUpstream(repo.config, branch, context.remoteName, refName)
          PushOutcome.Ok
        }
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

  /** A ref update whose server-side result is a success (created/updated or already current). */
  private fun RemoteRefUpdate.Status?.isAccepted(): Boolean =
    this == RemoteRefUpdate.Status.OK || this == RemoteRefUpdate.Status.UP_TO_DATE

  private companion object {
    const val REFS_HEADS = "refs/heads/"
  }
}
