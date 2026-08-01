package com.gitlab.eclipse.ci.actions

import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.JobTraceService
import com.gitlab.eclipse.ci.joblog.JobLogEditorOpener
import com.gitlab.eclipse.ci.joblog.JobLogGenerationRegistry
import com.gitlab.eclipse.ci.joblog.JobLogKey
import com.gitlab.eclipse.ci.joblog.TraceResult
import com.gitlab.eclipse.ci.joblog.fetchAndFormatTrace
import com.gitlab.eclipse.ci.joblog.readAuditMessage
import com.gitlab.eclipse.ci.joblog.stripTraceFormatting
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.sidebar.JobNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.runtime.ILog
import org.eclipse.swt.SWTException

internal const val SELECT_JOB_MESSAGE = "Select a job in the GitLab sidebar first."
internal const val NO_PROJECT_MESSAGE = "Couldn't determine the job's project; refresh the sidebar."

/** Non-committal on purpose (design §15): 403 is hidden as 404, so never confirm existence. */
internal const val LOG_NOT_FOUND_MESSAGE = "The log doesn't exist or you don't have access to it."
internal const val GENERIC_LOG_ERROR_MESSAGE = "Couldn't load the job log. Try again."

/**
 * Opens the trace of the job selected in the GitLab sidebar in an in-memory editor (design §14).
 * Thin SWT shell, same shape as [JobActionHandler]: resolve the selection on the UI thread, then
 * pin the connection + fetch in the background ([launchDisplayJobLog]) and marshal the result
 * back. There is NO [InFlightWriteGuard] — the READ is idempotent (design §13); instead every
 * invocation gets a UI-thread-assigned generation ([JobLogGenerationRegistry]) and only the
 * LATEST generation for a key may reflect into the editor or notify, so rapid re-runs converge
 * on the newest result and a stopped activation reflects nothing.
 */
@Suppress("unused")
class DisplayJobLogHandler(
  private val traceService: JobTraceService = JobTraceService(),
) : AbstractHandler() {
  private val log = logger<DisplayJobLogHandler>()
  private val apiClient by lazyService<GitLabApiClient>()
  private val coroutineScope by lazyService<CoroutineScope>()

  override fun execute(event: ExecutionEvent): Any? {
    // UI thread: resolve the selection + assign the generation synchronously before hopping.
    // The connection pin happens in the background — capturing it can block on an OAuth refresh.
    val node = selectedSidebarNode<JobNode>(event)
    if (node == null) {
      NotificationUtils.show(SELECT_JOB_MESSAGE)
      return null
    }
    val projectId = node.projectId
    if (projectId == null) {
      NotificationUtils.show(NO_PROJECT_MESSAGE)
      return null
    }
    val jobId = node.job.id
    val key = JobLogKey.of(node.sourceInstanceUrl, node.sourceAuthFingerprint, projectId, jobId)
    // UI thread: number this run and record it as the latest for the key, superseding any
    // still-in-flight prior run for the same job.
    val myGen = JobLogGenerationRegistry.nextGeneration(key)
    launchDisplayJobLog(
      coroutineScope,
      log,
      key,
      myGen,
      projectId,
      jobId,
      apiClient,
      traceService,
      node.sourceInstanceUrl,
      node.sourceAuthFingerprint,
    )
    return null
  }
}

/**
 * Pins the connection and runs one trace fetch in the background (top-level on purpose — same
 * shape as [launchCiWrite]). The pin ([pinnedConnectionFor]) runs INSIDE the launch, on the
 * scope's IO dispatcher: capturing the connection reads the token, which in OAuth mode may
 * perform a synchronous refresh HTTP call that must never block the UI thread. A null pin means
 * the connection changed since the sidebar was loaded and the READ is NOT sent (never to the
 * wrong instance) — notify instead. CancellationException is rethrown so cancellation
 * propagates cleanly and neither reflects nor notifies; any other unclassified throwable is
 * caught here — it must never escape and cancel the shared scope. Audit lines
 * ([readAuditMessage]) never contain a token or response body.
 */
internal fun launchDisplayJobLog(
  scope: CoroutineScope,
  log: ILog,
  key: JobLogKey,
  myGen: Long,
  projectId: Long,
  jobId: Long,
  apiClient: GitLabApiClient,
  traceService: JobTraceService,
  nodeInstanceUrl: String,
  nodeAuthFingerprint: String,
) {
  scope.launch {
    // Shared scope with a plain Job: nothing may escape this launch or every other
    // consumer of the scope loses its coroutines.
    try {
      val connection = pinnedConnectionFor(apiClient, nodeInstanceUrl, nodeAuthFingerprint)
      if (connection == null) {
        log.error(readAuditMessage("displayJobLog", nodeInstanceUrl, projectId, jobId, "connectionRejected"))
        notifyIfLatest(key, myGen, CONNECTION_CHANGED_MESSAGE)
        return@launch
      }
      val result = fetchAndFormatTrace(
        { traceService.getTrace(projectId, jobId, connection) },
        ::stripTraceFormatting,
      )
      when (result) {
        is TraceResult.Loaded -> reflectLatest(log, key, myGen, result.text)
        is TraceResult.Failed -> {
          log.error(
            readAuditMessage(
              "displayJobLog",
              nodeInstanceUrl,
              projectId,
              jobId,
              result.kind,
              result.httpStatus,
              result.correlationId,
            ),
          )
          notifyIfLatest(key, myGen, if (result.notCommittal) LOG_NOT_FOUND_MESSAGE else GENERIC_LOG_ERROR_MESSAGE)
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Unclassified escape: must not cancel the shared scope. Same token/body-free structured
      // audit line as the classified path; the exception rides along in the log entry.
      log.error(readAuditMessage("displayJobLog", nodeInstanceUrl, projectId, jobId, "unexpected"), e)
      notifyIfLatest(key, myGen, GENERIC_LOG_ERROR_MESSAGE)
    }
  }
}

/**
 * Marshals the loaded text to the UI thread and reflects it into the editor ONLY if this run is
 * still the active activation's latest generation for [key] (design §14.2 step 3). The gate and
 * the open/reload happen in the same UI turn, so they are atomic w.r.t. any newer run. asyncExec
 * itself can throw [SWTException] on a disposed Display — swallowed, never allowed to cancel the
 * shared scope.
 */
private fun reflectLatest(log: ILog, key: JobLogKey, myGen: Long, text: String) {
  try {
    currentDisplay.asyncExec {
      try {
        if (currentDisplay.isDisposed) return@asyncExec
        if (!JobLogGenerationRegistry.shouldAct(key, myGen)) return@asyncExec
        JobLogEditorOpener.openOrReload(key, text)
      } catch (ignored: SWTException) {
        /* display disposed mid-turn: no-op */
      } catch (e: Exception) {
        // open/reload can throw in-UI where the background catch can't see it (design §14.2).
        log.error("Job log editor open/reload failed for $key", e)
        if (JobLogGenerationRegistry.shouldAct(key, myGen)) {
          NotificationUtils.showOnUiThread(GENERIC_LOG_ERROR_MESSAGE)
        }
      }
    }
  } catch (ignored: SWTException) {
    /* asyncExec on disposed display: no-op — never let it cancel the shared scope */
  }
}

/**
 * Marshals a failure notification to the UI thread and shows it ONLY if this run is still the
 * active activation's latest generation for [key] — decided and displayed in the SAME UI turn
 * via [NotificationUtils.showOnUiThread] so no stale popup can slip through (design §14.4 R7).
 */
private fun notifyIfLatest(key: JobLogKey, myGen: Long, message: String) {
  try {
    currentDisplay.asyncExec {
      if (currentDisplay.isDisposed || !JobLogGenerationRegistry.active) return@asyncExec
      if (JobLogGenerationRegistry.isLatest(key, myGen)) NotificationUtils.showOnUiThread(message)
    }
  } catch (ignored: SWTException) {
    /* disposed: notification no-op (design §14.4) */
  }
}
