package com.gitlab.eclipse.ci.actions

import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.JobActionService
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.sidebar.BridgeNode
import com.gitlab.eclipse.views.sidebar.JobNode
import com.gitlab.eclipse.views.sidebar.SidebarNode
import kotlinx.coroutines.CoroutineScope
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.handlers.HandlerUtil

/**
 * Retries, cancels, or plays the job — or retries / plays the bridge (CI-06) — selected in the
 * GitLab sidebar (design §8.5). Same thin
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
    val node = jobWriteTargetOf(selectedSidebarNode<SidebarNode>(event), action)
    if (node == null) {
      NotificationUtils.show("Select a job in the GitLab sidebar first.")
      return null
    }
    val projectId = node.projectId
    if (projectId == null) {
      NotificationUtils.show("The job's project is unknown; refresh the sidebar.")
      return null
    }
    val jobId = node.jobId
    val key = jobWriteKeyOf(node)
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

/** Where a job write goes: the selected node's job (or bridge) id, project and source-connection tags. */
internal data class JobWriteTarget(
  val jobId: Long,
  val projectId: Long?,
  val sourceInstanceUrl: String,
  val sourceAuthFingerprint: String,
)

/**
 * The write target of [action] (`retry` / `cancel` / `play`) on the selected [node], or null when
 * the selection affords none. A [BridgeNode] (CI-06 §4.6) is addressed on the same job endpoints by
 * its bridge id — GitLab's retry and play resolve bridges too (`find_job!`) — but never for cancel,
 * which resolves builds only (`find_build!`) and answers 404 for a bridge; the menus already hide
 * it, and this keeps a stray invocation from sending a request bound to fail.
 */
internal fun jobWriteTargetOf(node: SidebarNode?, action: String): JobWriteTarget? = when (node) {
  is JobNode -> JobWriteTarget(node.job.id, node.projectId, node.sourceInstanceUrl, node.sourceAuthFingerprint)
  is BridgeNode ->
    if (action == "cancel") {
      null
    } else {
      JobWriteTarget(node.bridge.id, node.projectId, node.sourceInstanceUrl, node.sourceAuthFingerprint)
    }
  else -> null
}

/**
 * The in-flight serialization key of a write to [target]. Keyed by the NODE's instance tag: equal to
 * the snapshot-derived key whenever the pin would succeed (the pin requires the normalized urls to
 * match). A bridge shares the `"job"` key space on purpose: its id is a job id on the same endpoints,
 * so a job write and a bridge write to the same id serialize against each other.
 */
internal fun jobWriteKeyOf(target: JobWriteTarget): WriteKey =
  WriteKey(normalizeInstanceUrl(target.sourceInstanceUrl), "job", target.jobId)
