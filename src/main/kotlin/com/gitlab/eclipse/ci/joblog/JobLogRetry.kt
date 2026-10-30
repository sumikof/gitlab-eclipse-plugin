package com.gitlab.eclipse.ci.joblog

import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.JobActionService
import com.gitlab.eclipse.ci.actions.InFlightWriteGuard
import com.gitlab.eclipse.ci.actions.JobWriteTarget
import com.gitlab.eclipse.ci.actions.WriteKey
import com.gitlab.eclipse.ci.actions.jobWriteKeyOf
import com.gitlab.eclipse.ci.actions.launchCiWrite
import com.gitlab.eclipse.ci.actions.refreshAfterCiWrite
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CoroutineScope
import org.eclipse.ui.PlatformUI

/** The retry worked but GitLab's answer did not name the new job (§3.2): nothing new can be opened. */
internal const val RETRIED_NO_ID_MESSAGE = "The job was retried. Open the new job from the GitLab sidebar."

private const val RETRY_ACTION = "retry"

/**
 * Retry of the job a log editor shows (L4), shared by the waiting page's button and the log view's
 * command. One retry per job at a time ([InFlightWriteGuard], the same key as the sidebar's job
 * writes) and one successful retry per session ([JobLogSession.canRetry]). On success the new job's
 * log opens and the editor the retry came from closes, but only when the new editor really opened
 * (D-3 (b)); a result that arrives after that editor was closed or the plugin stopped opens nothing.
 *
 * The defaults are the production wiring; only they touch the workbench. UI thread only.
 */
internal class JobLogRetry(
  private val tryAcquire: (WriteKey) -> Boolean = InFlightWriteGuard::tryAcquire,
  private val release: (WriteKey) -> Unit = InFlightWriteGuard::release,
  private val launch: (WriteKey, JobLogEditorInput, onNewJob: (Long?) -> Unit, onDone: () -> Unit) -> Unit =
    ::launchRetryWrite,
  private val sessionOf: (JobLogKey) -> JobLogSession? = JobLogSessionManager::sessionFor,
  private val isActive: () -> Boolean = { JobLogGenerationRegistry.active },
  private val openNew: (JobLogTarget) -> Unit = { JobLogSessionManager.open(it, JobLogEditorOpener::show) },
  private val notify: (String) -> Unit = { NotificationUtils.show(it) },
  private val runOnUi: (() -> Unit) -> Unit = ::asyncOnUi,
) {
  /**
   * Retries the job of [input] unless it cannot be retried now or a write to it is in flight; false
   * then, and nothing changes. [setBusy] marks the control pressed until the write is done;
   * [closeOrigin] closes the editor the retry came from.
   */
  fun press(input: JobLogEditorInput, closeOrigin: () -> Unit, setBusy: (Boolean) -> Unit): Boolean {
    val session = sessionOf(input.key)?.takeIf { it.canRetry } ?: return false
    val target = JobWriteTarget(input.key.jobId, input.key.projectId, input.instanceUrl, input.authFingerprint)
    val key = jobWriteKeyOf(target)
    if (!tryAcquire(key)) return false
    // Both hops are queued from the write's coroutine, onNewJob first, so onDone sees it.
    var succeeded = false
    val onNewJob = { id: Long? ->
      runOnUi {
        succeeded = true
        newJob(session, input, id, closeOrigin)
      }
    }
    val onDone = {
      runOnUi {
        setBusy(false)
        if (!succeeded) session.retryFinished(succeeded = false)
      }
    }
    var started = false
    try {
      session.retryStarted()
      setBusy(true)
      launch(key, input, onNewJob, onDone)
      started = true
    } finally {
      // Nothing launched (or a launch that threw never runs its finally): free the job, the control and Retry.
      if (!started) {
        release(key)
        session.retryFinished(succeeded = false)
        setBusy(false)
      }
    }
    return true
  }

  private fun newJob(session: JobLogSession, input: JobLogEditorInput, id: Long?, closeOrigin: () -> Unit) {
    session.retryFinished(succeeded = true)
    // The editor was closed (a new session or none) or the plugin stopped while the write ran.
    if (!isActive() || sessionOf(input.key) !== session) return
    // Gson leaves a missing id at 0.
    val newJobId = id?.takeIf { it > 0 } ?: return notify(RETRIED_NO_ID_MESSAGE)
    val newKey = input.key.copy(jobId = newJobId)
    openNew(JobLogTarget(newKey, input.key.projectId, newJobId, input.instanceUrl, input.authFingerprint))
    // Opening never throws (a failure is reported and its session dropped): only an open editor counts.
    if (sessionOf(newKey)?.hasViews == true) closeOrigin()
  }
}

/** UI thread. The retry POST through [launchCiWrite]: its guard release, audit and failure notice. */
private fun launchRetryWrite(key: WriteKey, input: JobLogEditorInput, onNewJob: (Long?) -> Unit, onDone: () -> Unit) {
  val window = PlatformUI.getWorkbench().activeWorkbenchWindow
  val apiClient = service<GitLabApiClient>()
  val jobKey = input.key
  // Set by the call and read by onSuccess, one after the other in the same coroutine.
  var newJobId: Long? = null
  launchCiWrite(
    service<CoroutineScope>(),
    logger<JobLogEditor>(),
    key,
    RETRY_ACTION,
    jobKey.projectId,
    apiClient,
    input.instanceUrl,
    input.authFingerprint,
    onSuccess = {
      refreshAfterCiWrite(window)
      onNewJob(newJobId)
    },
    onDone = onDone,
    auditMessage = { outcome, exceptionClass ->
      jobLogWriteAuditMessage(RETRY_ACTION, jobKey.projectId, jobKey.jobId, outcome, exceptionClass)
    },
    attachThrowable = false,
  ) { connection ->
    val posted = JobActionService(apiClient).retryReturningJob(connection, jobKey.projectId, jobKey.jobId)
    newJobId = posted.body?.id
    posted.result
  }
}
