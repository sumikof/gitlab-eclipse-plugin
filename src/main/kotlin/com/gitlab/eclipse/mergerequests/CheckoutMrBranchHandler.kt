package com.gitlab.eclipse.mergerequests

import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.sidebar.MergeRequestNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.jface.viewers.IStructuredSelection
import org.eclipse.ui.handlers.HandlerUtil

/**
 * Checks out the source branch of the merge request selected in the GitLab sidebar
 * (VSCode `gl.checkoutMrBranch` parity, Phase 3 §8.4). Same-project MRs only — a fork MR's
 * source branch lives in another repository and is rejected up front.
 *
 * The MR is matched to a workspace repository by web URL: the candidate whose project
 * [RepositoryContext.webUrl] prefixes the MR's `webUrl` (`<projectWebUrl>/-/merge_requests/…`).
 * Zero or several matching repositories notify instead of guessing — this command WRITES to the
 * working tree, so it never picks a repository heuristically.
 */
@Suppress("unused")
class CheckoutMrBranchHandler(
  private val resolver: RepositoryContextResolver = RepositoryContextResolver(),
  private val checkoutService: MrBranchCheckoutService = MrBranchCheckoutService(),
) : AbstractHandler() {
  private val logger = logger<CheckoutMrBranchHandler>()
  private val coroutineScope by lazyService<CoroutineScope>()

  override fun execute(event: ExecutionEvent): Any? {
    // UI thread: capture the selection synchronously before hopping to the background.
    val mr = selectedMergeRequest(event)
    when {
      mr == null -> NotificationUtils.show("Select a merge request in the GitLab sidebar first.")
      mr.sourceProjectId == null || mr.sourceProjectId != mr.targetProjectId ->
        NotificationUtils.show("Checkout is only supported for same-project merge requests.")
      mr.sourceBranch.isNullOrBlank() || mr.sha.isNullOrBlank() ->
        NotificationUtils.show("The merge request has no source branch to check out.")
      else -> checkoutInBackground(mr, mr.sourceBranch)
    }
    logger.info("checkoutMrBranch requested.")
    return null
  }

  /** The [MergeRequestNode] the command was invoked on: the sidebar context-menu selection,
   *  falling back to the window's current selection. */
  private fun selectedMergeRequest(event: ExecutionEvent): GitLabMergeRequest? {
    val selection = HandlerUtil.getActiveMenuSelection(event)
      ?: HandlerUtil.getCurrentSelection(event)
    return ((selection as? IStructuredSelection)?.firstElement as? MergeRequestNode)?.mr
  }

  private fun checkoutInBackground(mr: GitLabMergeRequest, sourceBranch: String) {
    // Everything below is blocking I/O (JGit enumeration, fetch, checkout) — background only.
    coroutineScope.launch {
      // Shared scope with a plain Job: nothing may escape this launch or every other
      // consumer of the scope loses its coroutines.
      try {
        val matches = resolver.candidateContexts().filter { mrBelongsTo(it, mr) }
        when {
          matches.isEmpty() ->
            NotificationUtils.show("No workspace repository matches this merge request's project.")
          matches.size > 1 ->
            NotificationUtils.show(
              "Multiple workspace repositories match this merge request; cannot choose one.",
            )
          else -> notifyResult(checkoutService.checkout(matches[0], mr), sourceBranch)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.error("Failed to check out the merge request branch.", e)
        NotificationUtils.show("Checkout failed — see the Error Log.")
      }
    }
  }

  /** True when [mr]'s web URL lives under [context]'s project web URL. */
  private fun mrBelongsTo(context: RepositoryContext, mr: GitLabMergeRequest): Boolean =
    mr.webUrl.startsWith("${context.webUrl.trimEnd('/')}/-/merge_requests/")

  private fun notifyResult(result: CheckoutResult, sourceBranch: String) {
    when (result) {
      CheckoutResult.Ok -> NotificationUtils.show("Branch changed to $sourceBranch.")
      is CheckoutResult.OutOfSync ->
        if (result.checkedOut) {
          NotificationUtils.show("Checked out, but out of sync with the remote branch.")
        } else {
          NotificationUtils.show(
            "The merge request is out of sync with the remote branch; not checked out.",
          )
        }
      CheckoutResult.Diverged ->
        NotificationUtils.show("Local branch has diverged; not checked out.")
      CheckoutResult.StateBlocked ->
        NotificationUtils.show("Repository is mid-merge/rebase; resolve it first.")
      CheckoutResult.Busy ->
        NotificationUtils.show("Another git operation is running on this repository; try again.")
      is CheckoutResult.Failed -> {
        logger.error("Merge request branch checkout failed.", result.cause)
        NotificationUtils.show("Checkout failed — see the Error Log.")
      }
    }
  }
}
