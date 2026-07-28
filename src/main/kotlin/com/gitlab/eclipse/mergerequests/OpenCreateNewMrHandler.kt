package com.gitlab.eclipse.mergerequests

import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.navigation.BrowserLauncher
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File

/**
 * Opens the GitLab new-merge-request page for the current branch in the external browser
 * (VSCode `gl.openCreateNewMR` parity, Phase 3 §FR-3/§8.5).
 *
 * The page is opened directly when the current branch's upstream tracks the same-named branch
 * on the resolved GitLab remote (read-only PR-2 path, unchanged). Otherwise, with a clean
 * working tree, the branch is auto-pushed to the resolved remote first — the URL is opened
 * ONLY when the push actually updated the remote ref; a rejected or failed push notifies
 * instead, because the new-MR page for an unpushed branch is broken. A dirty working tree is
 * never pushed: the user is asked to commit first.
 */
@Suppress("unused")
class OpenCreateNewMrHandler(
  private val resolver: RepositoryContextResolver = RepositoryContextResolver(),
  private val reader: CurrentBranchGitReader = CurrentBranchGitReader(),
  private val browser: BrowserLauncher = BrowserLauncher(),
  private val pushService: BranchPushService = BranchPushService(),
) : AbstractHandler() {
  private val logger = logger<OpenCreateNewMrHandler>()
  private val coroutineScope by lazyService<CoroutineScope>()

  override fun execute(event: ExecutionEvent): Any? {
    // selectActiveContext must run on the UI thread (it captures the active editor at call
    // time; JGit resolution then runs in the background); a null context is a silent no-op
    // because the resolver already notified the user.
    resolver.selectActiveContext { context ->
      if (context != null) openCreateNewMr(context)
    }
    logger.info("openCreateNewMR requested.")
    return null
  }

  private fun openCreateNewMr(context: RepositoryContext) {
    // The callback fires on the UI thread, and everything below is JGit I/O (branch read,
    // status, possibly a network push) — hop to a background coroutine before doing it.
    coroutineScope.launch {
      try {
        val branch = reader.read(File(context.gitDir))
        val branchName = branch.name
        when {
          branchName == null -> NotificationUtils.show("No branch checked out.")
          isPushedToResolvedRemote(branch, context) ->
            browser.open(MrUrlBuilder.newMergeRequestUrl(context.webUrl, branchName))
          hasUncommittedChanges(context) ->
            NotificationUtils.show("Commit and push the branch before creating a merge request.")
          else -> pushAndOpen(context, branchName)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.error("Failed to open the new merge request page.", e)
        NotificationUtils.show("GitLab: Could not open the new merge request page.")
      }
    }
  }

  /**
   * True only when the upstream tracks the same-named branch on the resolved GitLab remote —
   * the sole case where the new-MR URL's source branch is known to exist server-side.
   */
  private fun isPushedToResolvedRemote(branch: CurrentBranch, context: RepositoryContext): Boolean =
    branch.hasUpstream &&
      branch.upstreamRemote == context.remoteName &&
      branch.trackingBranch == branch.name

  /** Read-only JGit status: staged or unstaged changes block the auto-push (untracked files
   *  don't — they wouldn't be part of the push anyway). */
  private fun hasUncommittedChanges(context: RepositoryContext): Boolean =
    FileRepositoryBuilder().setGitDir(File(context.gitDir)).setMustExist(true).build().use { repo ->
      Git(repo).status().call().hasUncommittedChanges()
    }

  /** Auto-push path (§8.5): the new-MR URL is opened ONLY on [PushOutcome.Ok] — every other
   *  outcome means the branch is not (verifiably) on the remote and the URL would be broken. */
  private fun pushAndOpen(context: RepositoryContext, branchName: String) {
    when (val outcome = pushService.push(context, branchName)) {
      PushOutcome.Ok ->
        browser.open(MrUrlBuilder.newMergeRequestUrl(context.webUrl, branchName))
      is PushOutcome.Rejected ->
        NotificationUtils.show(
          "Push was rejected (${outcome.status}) — the branch may be protected or behind. " +
            "Push it manually.",
        )
      is PushOutcome.Failed -> {
        logger.error("Auto-push before creating a merge request failed.", outcome.cause)
        NotificationUtils.show("GitLab: Push failed — see the Error Log.")
      }
      PushOutcome.Busy ->
        NotificationUtils.show("A git operation is already running for this repository.")
    }
  }
}
