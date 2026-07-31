package com.gitlab.eclipse.ci.actions

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.PipelineActionService
import com.gitlab.eclipse.api.PostResult
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.sidebar.GitLabSidebarView
import com.gitlab.eclipse.views.sidebar.PipelineNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.runtime.ILog
import org.eclipse.jface.viewers.IStructuredSelection
import org.eclipse.swt.widgets.Display
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.handlers.HandlerUtil

/**
 * Retries or cancels the pipeline selected in the GitLab sidebar (design §8.5). Thin SWT shell:
 * resolves the action from the command id and serializes writes per target
 * ([InFlightWriteGuard]) on the UI thread, then validates + pins the connection the node was
 * loaded over and runs the POST in the background ([launchCiWrite] — the pin happens off the
 * UI thread because [GitLabApiClient.captureConnection] may trigger a synchronous OAuth token
 * refresh; a null pin means the connection changed and NO write is issued). Refresh happens
 * only on success, on the UI thread; failures notify + audit-log instead.
 */
@Suppress("unused")
class PipelineActionHandler(
  private val actionService: PipelineActionService = PipelineActionService(),
) : AbstractHandler() {
  private val logger = logger<PipelineActionHandler>()
  private val apiClient by lazyService<GitLabApiClient>()
  private val coroutineScope by lazyService<CoroutineScope>()

  override fun execute(event: ExecutionEvent): Any? {
    // UI thread: resolve action + selection synchronously before hopping. The connection pin
    // happens in the background — capturing it can block on an OAuth token refresh.
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
    // Keyed by the NODE's instance tag: equal to the snapshot-derived key whenever the pin
    // would succeed (the pin requires the normalized urls to match), so serialization
    // semantics are unchanged.
    val key = WriteKey(normalizeInstanceUrl(node.sourceInstanceUrl), "pipeline", node.pipelineId)
    if (!InFlightWriteGuard.tryAcquire(key)) {
      logger.info("A write to pipeline #${node.pipelineId} is already in flight; ignoring.")
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
 * Pins the connection and runs one CI write in the background (shared shell of both handlers;
 * top-level function on purpose — no AbstractHandler base-class hierarchy). The pin
 * ([pinnedConnectionFor]) runs INSIDE the launch, on the scope's IO dispatcher: capturing the
 * connection reads the token, which in OAuth mode may perform a synchronous refresh HTTP call
 * that must never block the UI thread. A null pin notifies and issues NO write. The caller must
 * already hold [key]; it is released in `finally` so success, failure, a rejected pin, AND
 * cancellation free the target. CancellationException is rethrown so cancellation propagates
 * out of the launch cleanly; any other unclassified throwable (including one thrown by the
 * token refresh during the pin) is caught here — it must never escape and cancel the shared
 * scope. [window] is the workbench window the command was invoked from (captured on the UI
 * thread in `execute()`): the success refresh targets THAT window's sidebar, not whichever
 * window happens to be active when the POST completes.
 */
internal fun launchCiWrite(
  scope: CoroutineScope,
  log: ILog,
  key: WriteKey,
  action: String,
  projectId: Long,
  apiClient: GitLabApiClient,
  nodeInstanceUrl: String,
  nodeAuthFingerprint: String,
  window: IWorkbenchWindow?,
  call: (ConnectionSnapshot) -> PostResult,
) {
  scope.launch {
    // Shared scope with a plain Job: nothing may escape this launch or every other
    // consumer of the scope loses its coroutines.
    try {
      val connection = pinnedConnectionFor(apiClient, nodeInstanceUrl, nodeAuthFingerprint)
      if (connection == null) {
        NotificationUtils.show(CONNECTION_CHANGED_MESSAGE)
        return@launch
      }
      when (val outcome = classifyWrite { call(connection) }) {
        is WriteOutcome.Success -> {
          log.info(writeAuditMessage(action, key.instanceUrl, projectId, key.targetKind, key.targetId, outcome))
          Display.getDefault().asyncExec { findSidebarViewIn(window)?.refresh() }
        }
        is WriteOutcome.Failure -> {
          log.error(writeAuditMessage(action, key.instanceUrl, projectId, key.targetKind, key.targetId, outcome))
          NotificationUtils.show("The action failed. Refresh the sidebar to check the current state.")
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Unclassified escape (e.g. InterruptedException, client-rebuild RuntimeException):
      // must not cancel the shared scope. No refresh — state is unknown. Same structured
      // token-free audit line as the classified path (synthetic "unexpected" kind) so the
      // failure stays correlatable to the target instance; the exception rides along.
      log.error(
        writeAuditMessage(
          action,
          key.instanceUrl,
          projectId,
          key.targetKind,
          key.targetId,
          WriteOutcome.Failure(httpStatus = null, correlationId = null, failureKind = "unexpected"),
        ),
        e,
      )
      NotificationUtils.show("The action failed. Refresh the sidebar to check the current state.")
    } finally {
      InFlightWriteGuard.release(key)
    }
  }
}

/**
 * UI-thread-only lookup of the sidebar view in the GIVEN window (the one the action was invoked
 * from); null when the window or view is gone (nothing to refresh — `findView` handles both).
 */
private fun findSidebarViewIn(window: IWorkbenchWindow?): GitLabSidebarView? =
  window?.activePage?.findView(GitLabSidebarView.VIEW_ID) as? GitLabSidebarView
