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
import java.io.File

/**
 * Opens the GitLab new-merge-request page for the current branch in the external browser
 * (VSCode `gl.openCreateNewMR` parity, Phase 3 §FR-3 — read-only path).
 *
 * The page is opened directly only when the current branch's upstream tracks the same-named
 * branch on the resolved GitLab remote; otherwise the user is asked to push first. Auto-push
 * is deliberately deferred to PR-3 — this handler never writes to the repository or the remote.
 */
@Suppress("unused")
class OpenCreateNewMrHandler(
  private val resolver: RepositoryContextResolver = RepositoryContextResolver(),
  private val reader: CurrentBranchGitReader = CurrentBranchGitReader(),
  private val browser: BrowserLauncher = BrowserLauncher(),
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
    // The callback fires on the UI thread, and the branch read is JGit I/O — hop to a
    // background coroutine before doing it.
    coroutineScope.launch {
      try {
        val branch = reader.read(File(context.gitDir))
        val branchName = branch.name
        when {
          branchName == null -> NotificationUtils.show("No branch checked out.")
          isPushedToResolvedRemote(branch, context) ->
            browser.open(MrUrlBuilder.newMergeRequestUrl(context.webUrl, branchName))
          else ->
            NotificationUtils.show(
              "Push the branch to ${context.remoteName} before creating a merge request.",
            )
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
}
