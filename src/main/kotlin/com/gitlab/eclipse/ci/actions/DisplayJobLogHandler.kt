package com.gitlab.eclipse.ci.actions

import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.JobTraceService
import com.gitlab.eclipse.ci.joblog.JobLogEditorOpener
import com.gitlab.eclipse.ci.joblog.JobLogGenerationRegistry
import com.gitlab.eclipse.ci.joblog.JobLogKey
import com.gitlab.eclipse.ci.joblog.JobLogLoad
import com.gitlab.eclipse.ci.joblog.RenderResult
import com.gitlab.eclipse.ci.joblog.fetchAndRenderTrace
import com.gitlab.eclipse.ci.joblog.jobLogAuditMessage
import com.gitlab.eclipse.ci.joblog.renderInitial
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

private const val AUDIT_ACTION = "displayJobLog"

/**
 * Opens the trace of the job selected in the GitLab sidebar in the folding, colored job-log
 * editor ([com.gitlab.eclipse.ci.joblog.JobLogEditor], design §14 / B §5.3).
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
 * Pins the connection and runs one trace fetch + render in the background (top-level on purpose —
 * same shape as [launchCiWrite]). The pin ([pinnedConnectionFor]) runs INSIDE the launch, on the
 * scope's IO dispatcher: capturing the connection reads the token, which in OAuth mode may
 * perform a synchronous refresh HTTP call that must never block the UI thread. A null pin means
 * the connection changed since the sidebar was loaded and the READ is NOT sent (never to the
 * wrong instance) — notify instead. The trace tail is parsed here too ([renderInitial]), off the
 * UI thread (AC-B1-3). CancellationException is rethrown so cancellation propagates cleanly and
 * neither reflects nor notifies; any other unclassified throwable is caught here — it must never
 * escape and cancel the shared scope. Audit lines ([jobLogAuditMessage]) carry no URL, path,
 * token or log text, and no throwable is ever handed to [log] (only the exception class name).
 *
 * [reflect] and [notify] are the UI hops; their defaults are the only lines that touch the
 * Display, so tests replace them and run headless.
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
  reflect: (RenderResult) -> Unit = { reflectLatest(log, key, myGen, it, nodeInstanceUrl, nodeAuthFingerprint) },
  notify: (String) -> Unit = { notifyIfLatest(key, myGen, it) },
) {
  scope.launch {
    // Shared scope with a plain Job: nothing may escape this launch or every other
    // consumer of the scope loses its coroutines.
    try {
      val connection = pinnedConnectionFor(apiClient, nodeInstanceUrl, nodeAuthFingerprint)
      if (connection == null) {
        log.error(jobLogAuditMessage(AUDIT_ACTION, projectId, jobId, "connectionRejected"))
        notify(CONNECTION_CHANGED_MESSAGE)
        return@launch
      }
      val load = fetchAndRenderTrace(
        { traceService.getTraceSnapshot(projectId, jobId, null, connection) },
        { bytes, omittedBytes -> renderInitial(bytes, omittedBytes).first },
      )
      when (load) {
        is JobLogLoad.Rendered -> reflect(load.result)
        is JobLogLoad.Failed -> {
          val failure = load.failure
          log.error(
            jobLogAuditMessage(
              AUDIT_ACTION,
              projectId,
              jobId,
              failure.kind,
              failure.httpStatus,
              failure.correlationId,
            ),
          )
          notify(if (failure.notCommittal) LOG_NOT_FOUND_MESSAGE else GENERIC_LOG_ERROR_MESSAGE)
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Unclassified escape: must not cancel the shared scope. Same structured audit line as the
      // classified path, with the exception class only (never the throwable: its message or
      // stack may carry a URL or log text).
      val exceptionClass = e.javaClass.simpleName
      log.error(jobLogAuditMessage(AUDIT_ACTION, projectId, jobId, "unexpected", exceptionClass = exceptionClass))
      notify(GENERIC_LOG_ERROR_MESSAGE)
    }
  }
}

/**
 * Marshals the rendered log to the UI thread and reflects it into the editor ONLY if this run is
 * still the active activation's latest generation for [key] (design §14.2 step 3). The gate and
 * the open/reload happen in the same UI turn, so they are atomic w.r.t. any newer run. asyncExec
 * itself can throw [SWTException] on a disposed Display — swallowed, never allowed to cancel the
 * shared scope. The `currentDisplay` lookup itself can throw [IllegalStateException] from this
 * background thread once the workbench is torn down — also swallowed: the helper must be total
 * (never throw), or the launch's terminal catch would propagate it and cancel the shared scope.
 */
private fun reflectLatest(
  log: ILog,
  key: JobLogKey,
  myGen: Long,
  rendered: RenderResult,
  instanceUrl: String,
  authFingerprint: String,
) {
  try {
    currentDisplay.asyncExec {
      try {
        if (currentDisplay.isDisposed) return@asyncExec
        if (!JobLogGenerationRegistry.shouldAct(key, myGen)) return@asyncExec
        JobLogEditorOpener.openOrReload(key, rendered, instanceUrl, authFingerprint)
      } catch (ignored: SWTException) {
        /* display disposed mid-turn: no-op */
      } catch (e: Exception) {
        // open/reload can throw in-UI where the background catch can't see it (design §14.2).
        val exceptionClass = e.javaClass.simpleName
        log.error(
          jobLogAuditMessage(
            AUDIT_ACTION,
            key.projectId,
            key.jobId,
            "editorFailed",
            exceptionClass = exceptionClass,
          ),
        )
        if (JobLogGenerationRegistry.shouldAct(key, myGen)) {
          NotificationUtils.showOnUiThread(GENERIC_LOG_ERROR_MESSAGE)
        }
      }
    }
  } catch (ignored: SWTException) {
    /* asyncExec on disposed display: no-op — never let it cancel the shared scope */
  } catch (ignored: IllegalStateException) {
    /* workbench torn down (background-thread display lookup): nothing to reflect */
  }
}

/**
 * Marshals a failure notification to the UI thread and shows it ONLY if this run is still the
 * active activation's latest generation for [key] — decided and displayed in the SAME UI turn
 * via [NotificationUtils.showOnUiThread] so no stale popup can slip through (design §14.4 R7).
 * Total (never throws): the `currentDisplay` lookup can throw [IllegalStateException] from this
 * background thread once the workbench is torn down, and the launch's terminal catch calls this
 * helper — an escape here would cancel the shared scope.
 */
private fun notifyIfLatest(key: JobLogKey, myGen: Long, message: String) {
  try {
    currentDisplay.asyncExec {
      if (currentDisplay.isDisposed || !JobLogGenerationRegistry.active) return@asyncExec
      if (JobLogGenerationRegistry.isLatest(key, myGen)) NotificationUtils.showOnUiThread(message)
    }
  } catch (ignored: SWTException) {
    /* disposed: notification no-op (design §14.4) */
  } catch (ignored: IllegalStateException) {
    /* workbench torn down (background-thread display lookup): notification no-op */
  }
}
