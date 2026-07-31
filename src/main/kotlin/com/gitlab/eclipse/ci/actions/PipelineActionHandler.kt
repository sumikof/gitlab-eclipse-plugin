package com.gitlab.eclipse.ci.actions

import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.PipelineActionService
import com.gitlab.eclipse.api.PostResult
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.sidebar.GitLabSidebarView
import com.gitlab.eclipse.views.sidebar.PipelineNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.runtime.ILog
import org.eclipse.jface.viewers.IStructuredSelection
import org.eclipse.swt.widgets.Display
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.handlers.HandlerUtil

/**
 * Retries or cancels the pipeline selected in the GitLab sidebar (design §8.5). Thin SWT shell:
 * resolves the action from the command id, validates + pins the connection the node was loaded
 * over ([pinnedConnectionFor] — null means the connection changed and NO write is issued),
 * serializes writes per target ([InFlightWriteGuard]), and runs the POST in the background.
 * Refresh happens only on success, on the UI thread; failures notify + audit-log instead.
 */
@Suppress("unused")
class PipelineActionHandler(
  private val actionService: PipelineActionService = PipelineActionService(),
) : AbstractHandler() {
  private val logger = logger<PipelineActionHandler>()
  private val apiClient by lazyService<GitLabApiClient>()
  private val coroutineScope by lazyService<CoroutineScope>()

  override fun execute(event: ExecutionEvent): Any? {
    // UI thread: resolve action + selection + connection synchronously before hopping.
    val action = when (event.command.id) {
      "com.gitlab.eclipse.commands.RetryPipeline" -> "retry"
      "com.gitlab.eclipse.commands.CancelPipeline" -> "cancel"
      else -> {
        NotificationUtils.show("Unknown pipeline action.")
        return null
      }
    }
    val node = selectedSidebarNode<PipelineNode>(event)
    if (node == null) {
      NotificationUtils.show("Select a pipeline in the GitLab sidebar first.")
      return null
    }
    val projectId = node.projectId
    if (projectId == null) {
      NotificationUtils.show("The pipeline's project is unknown; refresh the sidebar.")
      return null
    }
    val connection = pinnedConnectionFor(apiClient, node.sourceInstanceUrl, node.sourceAuthFingerprint)
    if (connection == null) {
      NotificationUtils.show(CONNECTION_CHANGED_MESSAGE)
      return null
    }
    val key = WriteKey(normalizeInstanceUrl(connection.instanceUrl), "pipeline", node.pipelineId)
    if (!InFlightWriteGuard.tryAcquire(key)) {
      logger.info("A write to pipeline #${node.pipelineId} is already in flight; ignoring.")
      return null
    }
    launchCiWrite(coroutineScope, logger, key, action, projectId) {
      when (action) {
        "retry" -> actionService.retry(connection, projectId, node.pipelineId)
        else -> actionService.cancel(connection, projectId, node.pipelineId)
      }
    }
    return null
  }
}

internal const val CONNECTION_CHANGED_MESSAGE =
  "The GitLab connection changed since this was loaded. Refresh the sidebar and try again."

/** The node the command was invoked on: sidebar context-menu selection, else current selection. */
internal inline fun <reified T> selectedSidebarNode(event: ExecutionEvent): T? {
  val selection = HandlerUtil.getActiveMenuSelection(event)
    ?: HandlerUtil.getCurrentSelection(event)
  return (selection as? IStructuredSelection)?.firstElement as? T
}

/**
 * Runs one pinned CI write in the background (shared shell of both handlers; top-level function
 * on purpose — no AbstractHandler base-class hierarchy). The caller must already hold [key];
 * it is released in `finally` so success, failure, AND cancellation free the target.
 * [classifyWrite] rethrows CancellationException, and nothing here catches it, so cancellation
 * propagates out of the launch cleanly.
 */
internal fun launchCiWrite(
  scope: CoroutineScope,
  log: ILog,
  key: WriteKey,
  action: String,
  projectId: Long,
  call: () -> PostResult,
) {
  scope.launch {
    // Shared scope with a plain Job: nothing may escape this launch or every other
    // consumer of the scope loses its coroutines.
    try {
      when (val outcome = classifyWrite(call)) {
        is WriteOutcome.Success -> {
          log.info(writeAuditMessage(action, key.instanceUrl, projectId, key.targetKind, key.targetId, outcome))
          Display.getDefault().asyncExec { findSidebarView()?.refresh() }
        }
        is WriteOutcome.Failure -> {
          log.error(writeAuditMessage(action, key.instanceUrl, projectId, key.targetKind, key.targetId, outcome))
          NotificationUtils.show("The action failed. Refresh the sidebar to check the current state.")
        }
      }
    } finally {
      InFlightWriteGuard.release(key)
    }
  }
}

/** UI-thread-only lookup of the sidebar view; null when the view is not open (nothing to refresh). */
internal fun findSidebarView(): GitLabSidebarView? =
  PlatformUI.getWorkbench().activeWorkbenchWindow?.activePage
    ?.findView(GitLabSidebarView.VIEW_ID) as? GitLabSidebarView
