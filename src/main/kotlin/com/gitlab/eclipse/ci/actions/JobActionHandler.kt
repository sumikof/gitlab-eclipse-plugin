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

/**
 * Retries, cancels, or plays the job selected in the GitLab sidebar (design §8.5). Same thin
 * SWT shell as [PipelineActionHandler]: command id → action, connection validation + pinning
 * (null → notify, NO write), per-target in-flight serialization, background POST via
 * [launchCiWrite] — refresh only on success (UI thread), notify + audit on failure.
 */
@Suppress("unused")
class JobActionHandler(
  private val actionService: JobActionService = JobActionService(),
) : AbstractHandler() {
  private val logger = logger<JobActionHandler>()
  private val apiClient by lazyService<GitLabApiClient>()
  private val coroutineScope by lazyService<CoroutineScope>()

  override fun execute(event: ExecutionEvent): Any? {
    // UI thread: resolve action + selection + connection synchronously before hopping.
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
    val connection = pinnedConnectionFor(apiClient, node.sourceInstanceUrl, node.sourceAuthFingerprint)
    if (connection == null) {
      NotificationUtils.show(CONNECTION_CHANGED_MESSAGE)
      return null
    }
    val key = WriteKey(normalizeInstanceUrl(connection.instanceUrl), "job", jobId)
    if (!InFlightWriteGuard.tryAcquire(key)) {
      logger.info("A write to job #$jobId is already in flight; ignoring.")
      return null
    }
    launchCiWrite(coroutineScope, logger, key, action, projectId) {
      when (action) {
        "retry" -> actionService.retry(connection, projectId, jobId)
        "cancel" -> actionService.cancel(connection, projectId, jobId)
        else -> actionService.play(connection, projectId, jobId)
      }
    }
    return null
  }
}
