package com.gitlab.eclipse.mergerequests

import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.resources.IResource
import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.RefSpec
import java.io.File

/**
 * Outcome of [MrBranchCheckoutService.checkout]. Exactly one of these is returned; the service
 * never throws.
 */
sealed interface CheckoutResult {
  /** Checked out and the final HEAD SHA equals the MR's recorded SHA. */
  data object Ok : CheckoutResult

  /**
   * The MR's recorded SHA does not match reality. With [checkedOut] `false` the fetched
   * remote-tracking tip differed from `mr.sha`, nothing was checked out, and [headSha] is the
   * fetched tip (the MR record is stale — the caller may re-fetch the MR and retry). With
   * [checkedOut] `true` the branch WAS checked out but the final HEAD verification failed;
   * [headSha] is the actual HEAD.
   */
  data class OutOfSync(val headSha: String?, val checkedOut: Boolean) : CheckoutResult

  /** A local branch of the same name has commits not reachable from the fetched tip; nothing
   *  was checked out and nothing was reset. */
  data object Diverged : CheckoutResult

  /** The repository is mid-merge/rebase/cherry-pick etc. ([RepositoryState] not SAFE);
   *  nothing was fetched or checked out. */
  data object StateBlocked : CheckoutResult

  /** Another guarded git operation is already running on this repository
   *  ([GitOperationGuard.withRepo] rejected the call); nothing was done. */
  data object Busy : CheckoutResult

  /** Precondition violation (fork MR, missing branch/SHA) or any thrown failure. */
  data class Failed(val cause: Throwable?) : CheckoutResult
}

/**
 * Checks out a merge request's source branch locally (Phase 3 §8.4/§16): fetch the branch from
 * the context's remote with an explicit refspec, verify the fetched SHA against the MR record,
 * create/switch/fast-forward the local branch, and verify the final HEAD SHA. Same-project MRs
 * only. Never pushes, never resets, never forces; a diverged local branch is left untouched.
 *
 * Never throws — every failure maps to a [CheckoutResult]. Blocking git/network I/O: call from
 * a background thread.
 */
class MrBranchCheckoutService(
  private val guard: GitOperationGuard = service(),
  private val auth: GitAuthConfigurer = GitAuthConfigurer(),
) {
  private val logger by lazy { logger<MrBranchCheckoutService>() }

  fun checkout(context: RepositoryContext, mr: GitLabMergeRequest): CheckoutResult =
    try {
      val sourceBranch = mr.sourceBranch
      val expectedSha = mr.sha
      when {
        // Defense in depth: the context menu already restricts to same-project MRs, but a
        // fork MR's source branch lives in ANOTHER repository — fetching it from this
        // remote would be wrong, so reject before touching anything.
        mr.sourceProjectId == null || mr.sourceProjectId != mr.targetProjectId ->
          CheckoutResult.Failed(
            IllegalArgumentException("Cross-project (fork) merge requests cannot be checked out."),
          )
        sourceBranch.isNullOrBlank() || expectedSha.isNullOrBlank() ->
          CheckoutResult.Failed(
            IllegalArgumentException("The merge request has no source branch or no SHA."),
          )
        else ->
          guard.withRepo(context.gitDir) { fetchAndCheckout(context, sourceBranch, expectedSha) }
            ?: CheckoutResult.Busy
      }
    } catch (e: Exception) {
      CheckoutResult.Failed(e)
    }

  /**
   * Runs inside the guard. Staged: open repo → state precondition → fetch → fetched-SHA
   * verification → local-branch create/switch/fast-forward → final HEAD-SHA verification →
   * best-effort workspace refresh.
   */
  private fun fetchAndCheckout(
    context: RepositoryContext,
    sourceBranch: String,
    expectedSha: String,
  ): CheckoutResult =
    FileRepositoryBuilder().setGitDir(File(context.gitDir)).setMustExist(true).build().use { repo ->
      // Precondition (§16): never touch a repository that is mid-merge/rebase/cherry-pick.
      if (repo.repositoryState != RepositoryState.SAFE) return CheckoutResult.StateBlocked

      val trackingRef = "refs/remotes/${context.remoteName}/$sourceBranch"
      fetchSourceBranch(repo, context, sourceBranch)
      val fetched = repo.resolve(trackingRef)
        ?: return CheckoutResult.Failed(
          IllegalStateException("Fetch did not update $trackingRef."),
        )
      // The whole point: never check out a revision other than the MR's recorded SHA. A
      // mismatch means the MR record is stale (or the branch moved); the caller decides
      // whether to re-fetch the MR and retry.
      if (fetched.name != expectedSha) {
        return CheckoutResult.OutOfSync(headSha = fetched.name, checkedOut = false)
      }

      switchToBranch(repo, context.remoteName, sourceBranch, fetched)?.let { return it }

      val headSha = repo.resolve("HEAD")?.name
      if (headSha != expectedSha) {
        return CheckoutResult.OutOfSync(headSha = headSha, checkedOut = true)
      }
      refreshWorkspaceBestEffort()
      CheckoutResult.Ok
    }

  /** Fetches ONLY the MR source branch, with a forced explicit refspec into the remote-tracking
   *  ref so [fetchAndCheckout] can verify exactly what arrived. */
  private fun fetchSourceBranch(repo: Repository, context: RepositoryContext, sourceBranch: String) {
    val refSpec =
      RefSpec("+refs/heads/$sourceBranch:refs/remotes/${context.remoteName}/$sourceBranch")
    val fetch = Git(repo).fetch().setRemote(context.remoteName).setRefSpecs(refSpec)
    // Credentials are scoped per transport by GitAuthConfigurer against the fetch transport's URI.
    auth.applyAuth(fetch, context.instanceUrl).call()
  }

  /**
   * Returns null on success, or the terminal [CheckoutResult] that stops the operation.
   * No local branch → create it tracking the remote-tracking ref. Existing branch: tip equal
   * to [fetched] → plain switch; strictly behind → switch + fast-forward-only merge; anything
   * else → [CheckoutResult.Diverged] (never reset, never force).
   */
  private fun switchToBranch(
    repo: Repository,
    remoteName: String,
    sourceBranch: String,
    fetched: ObjectId,
  ): CheckoutResult? {
    val git = Git(repo)
    val localRef = repo.exactRef("refs/heads/$sourceBranch")
    if (localRef == null) {
      git.checkout()
        .setName(sourceBranch)
        .setCreateBranch(true)
        .setStartPoint("refs/remotes/$remoteName/$sourceBranch")
        .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
        .call()
      return null
    }
    val localTip = localRef.objectId
      ?: return CheckoutResult.Failed(
        IllegalStateException("Local branch '$sourceBranch' has no resolvable tip."),
      )
    return when {
      localTip == fetched -> {
        git.checkout().setName(sourceBranch).call()
        null
      }
      isFastForwardable(repo, localTip, fetched) -> fastForward(git, sourceBranch, fetched)
      else -> CheckoutResult.Diverged
    }
  }

  /** True when [localTip] is an ancestor of [fetched], i.e. the local branch can fast-forward. */
  private fun isFastForwardable(repo: Repository, localTip: ObjectId, fetched: ObjectId): Boolean =
    RevWalk(repo).use { walk ->
      walk.isMergedInto(walk.parseCommit(localTip), walk.parseCommit(fetched))
    }

  /** Switches to [sourceBranch] and fast-forwards it to [fetched] (FF_ONLY — a real merge is
   *  impossible here because the caller proved ancestry, but FF_ONLY makes it structural). */
  private fun fastForward(git: Git, sourceBranch: String, fetched: ObjectId): CheckoutResult? {
    git.checkout().setName(sourceBranch).call()
    val result = git.merge().include(fetched).setFastForward(MergeCommand.FastForwardMode.FF_ONLY).call()
    return if (result.mergeStatus.isSuccessful) {
      null
    } else {
      CheckoutResult.Failed(
        IllegalStateException("Fast-forward of '$sourceBranch' failed: ${result.mergeStatus}."),
      )
    }
  }

  /** Best-effort Eclipse resource refresh after a successful checkout (R-1: keeps the EGit
   *  index/decorations aligned with the new worktree). Any failure — including running outside
   *  a workbench, e.g. in tests — is logged and ignored. */
  private fun refreshWorkspaceBestEffort() {
    try {
      ResourcesPlugin.getWorkspace().root.refreshLocal(IResource.DEPTH_INFINITE, null)
    } catch (e: Exception) {
      logger.warn("Workspace refresh after checkout failed; refresh projects manually.", e)
    } catch (e: LinkageError) {
      logger.warn("Workspace refresh after checkout unavailable.", e)
    }
  }
}
