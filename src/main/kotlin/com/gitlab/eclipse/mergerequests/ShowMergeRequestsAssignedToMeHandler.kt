package com.gitlab.eclipse.mergerequests

import com.gitlab.eclipse.api.CurrentUserService
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.navigation.BrowserLauncher
import com.gitlab.eclipse.navigation.GitLabProjectUrlResolver
import com.gitlab.eclipse.navigation.WorkspaceProjectPicker
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent

/**
 * Opens `${projectWebUrl}/-/merge_requests?assignee_id=${currentUserId}` in the external browser
 * (VSCode `gl.showMergeRequests` parity).
 */
@Suppress("unused")
class ShowMergeRequestsAssignedToMeHandler(
  // TODO(PR-2): full §6.1 repository selection (RepositoryContext); PR-1 reuses the Phase 2 picker.
  private val picker: WorkspaceProjectPicker = WorkspaceProjectPicker(),
  private val currentUserService: CurrentUserService = service(),
  private val browser: BrowserLauncher = BrowserLauncher(),
) : AbstractHandler() {
  private val logger = logger<ShowMergeRequestsAssignedToMeHandler>()
  private val coroutineScope by lazyService<CoroutineScope>()

  override fun execute(event: ExecutionEvent): Any? {
    picker.pickWebUrl { r ->
      when (r) {
        is GitLabProjectUrlResolver.Resolution.Ok -> openAssignedMergeRequests(r.url)
        is GitLabProjectUrlResolver.Resolution.Warn -> NotificationUtils.show(r.message)
      }
    }
    logger.info("showMergeRequestsAssignedToMe requested.")
    return null
  }

  private fun openAssignedMergeRequests(projectWebUrl: String) {
    // pickWebUrl may invoke its callback on the UI thread (multi-repo dialog case), and
    // getCurrentUser() is blocking HTTP — hop to a background coroutine before calling it.
    // The try wraps the whole body: the coroutine scope is shared, so nothing may escape.
    coroutineScope.launch {
      try {
        val userId = currentUserService.getCurrentUser().id
        browser.open(MrUrlBuilder.assignedMergeRequestsUrl(projectWebUrl, userId))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.error("Failed to open assigned merge requests.", e)
        NotificationUtils.show("GitLab: Could not open merge requests. Check your token and connection.")
      }
    }
  }
}
