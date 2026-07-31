package com.gitlab.eclipse.ci.actions

import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.JobActionService
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.sidebar.JobNode
import kotlinx.coroutines.CoroutineScope
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.handlers.HandlerUtil

/**
 * Retries, cancels, or plays the job selected in the GitLab sidebar (design §8.5). Same thin
 * SWT shell as [PipelineActionHandler]: command id → action and per-target in-flight
 * serialization on the UI thread, then connection validation + pinning (null → notify, NO
 * write) and the POST in the background via [launchCiWrite] — the pin runs off the UI thread
 * because capturing it may trigger a synchronous OAuth token refresh. Refresh only on success
 * (UI thread), notify + audit on failure.
 */
@Suppress("unused")
class JobActionHandler(
  private val actionService: JobActionService = JobActionService(),
) : AbstractHandler() {
  private val logger = logger<JobActionHandler>()
  private val apiClient by lazyService<GitLabApiClient>()
  private val coroutineScope by lazyService<CoroutineScope>()

  override fun execute(event: ExecutionEvent): Any? {
    // UI thread: resolve action + selection synchronously before hopping. The connection pin
    // happens in the background — capturing it can block on an OAuth token refresh.
    val action = when (event.command.id) {
      "com.gitlab.eclipse.commands.RetryJob" -> "retry"
      "com.gitlab.eclipse.commands.CancelJob" -> "cancel"
      "com.gitlab.eclipse.commands.PlayJob" -> "play"
      else -> {
        NotificationUtils.show("Unknown job action.")
        return null
      }
    }
    val node = selectedSidebarNode<JobNode>(event)
    if (node == null) {
      NotificationUtils.show("Select a job in the GitLab sidebar first.")
      return null
    }
    val projectId = node.projectId
    if (projectId == null) {
      NotificationUtils.show("The job's project is unknown; refresh the sidebar.")
      return null
    }
    val jobId = node.job.id
    // Keyed by the NODE's instance tag: equal to the snapshot-derived key whenever the pin
    // would succeed (the pin requires the normalized urls to match), so serialization
    // semantics are unchanged.
    val key = WriteKey(normalizeInstanceUrl(node.sourceInstanceUrl), "job", jobId)
    if (!InFlightWriteGuard.tryAcquire(key)) {
      logger.info("A write to job #$jobId is already in flight; ignoring.")
      return null
    }
    launchCiWrite(
      coroutineScope,
      logger,
      key,
      action,
      projectId,
      apiClient,
      node.sourceInstanceUrl,
      node.sourceAuthFingerprint,
      HandlerUtil.getActiveWorkbenchWindow(event),
    ) { connection ->
      when (action) {
        "retry" -> actionService.retry(connection, projectId, jobId)
        "cancel" -> actionService.cancel(connection, projectId, jobId)
        else -> actionService.play(connection, projectId, jobId)
      }
    }
    return null
  }
}
