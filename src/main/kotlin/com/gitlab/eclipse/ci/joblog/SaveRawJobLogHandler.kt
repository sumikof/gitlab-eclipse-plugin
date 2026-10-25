package com.gitlab.eclipse.ci.joblog

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.JobTraceService
import com.gitlab.eclipse.ci.actions.CONNECTION_CHANGED_MESSAGE
import com.gitlab.eclipse.ci.actions.pinnedConnectionFor
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.runtime.ILog
import org.eclipse.swt.SWT
import org.eclipse.swt.widgets.FileDialog
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.handlers.HandlerUtil
import java.io.IOException
import java.net.http.HttpTimeoutException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

internal const val RAW_LOG_SAVED_MESSAGE = "Saved the raw job log."
internal const val RAW_LOG_SAVE_FAILED_MESSAGE = "Couldn't save the raw job log."

private const val AUDIT_ACTION = "saveRawJobLog"

/**
 * "Save Raw Log" on the job-log editor (design B §5.4): streams the whole, unformatted trace of the
 * open job to a file the user picks, even when the editor shows only its tail. The dialog runs on
 * the UI thread; the pin ([pinnedConnectionFor], same connection the log was read from) and the
 * download run in the background, because the pin may refresh an OAuth token over the network.
 * [RawTraceWriter] keeps the target either untouched or complete, also when the scope is cancelled
 * mid-download ([downloadRawTraceCancellably]).
 *
 * Neither the notifications nor the audit lines ([jobLogAuditMessage]) carry the path, the URL or
 * any log text, and no throwable is handed to [log]: a JDK file-system exception message can
 * contain the path, so only the exception class name is recorded.
 *
 * Every collaborator is a constructor seam so [run] is headless-testable; the defaults are the
 * only code that touches SWT, the workbench or the service registry.
 */
@Suppress("unused")
class SaveRawJobLogHandler(
  private val chooseTarget: (suggestedName: String) -> Path? = ::promptForRawLogTarget,
  private val pin: (instanceUrl: String, authFingerprint: String) -> ConnectionSnapshot? = { url, fingerprint ->
    pinnedConnectionFor(service(), url, fingerprint)
  },
  private val download: suspend (
    projectId: Long,
    jobId: Long,
    ConnectionSnapshot,
    Path,
  ) -> Unit = { projectId, jobId, c, t ->
    downloadRawTraceCancellably(JobTraceService(), projectId, jobId, c, t)
  },
  private val notify: (String) -> Unit = { NotificationUtils.show(it) },
  private val background: (JobLogKey, suspend () -> Unit) -> Unit = JobLogSessionManager::launchSave,
  private val log: ILog = logger<SaveRawJobLogHandler>(),
) : AbstractHandler() {
  override fun execute(event: ExecutionEvent): Any? {
    run((HandlerUtil.getActiveEditor(event) as? JobLogEditor)?.jobLogInput)
    return null
  }

  /** UI thread. Asks for the target, then saves in the background. Nothing happens on cancel. */
  internal fun run(input: JobLogEditorInput?) {
    // The command is only shown in the job-log editor, so a missing input is not worth a notice.
    if (input == null) return
    val projectId = input.key.projectId
    val jobId = input.key.jobId
    val target = chooseTarget("job-$jobId.log") ?: return
    fun failed(e: Exception, kind: String, httpStatus: Int? = null, correlationId: String? = null) {
      val exceptionClass = e.javaClass.simpleName
      log.error(jobLogAuditMessage(AUDIT_ACTION, projectId, jobId, kind, httpStatus, correlationId, exceptionClass))
      notify(RAW_LOG_SAVE_FAILED_MESSAGE)
    }
    background(input.key) {
      // Shared scope: nothing but cancellation may escape, or the scope's other coroutines stop.
      try {
        val connection = pin(input.instanceUrl, input.authFingerprint)
        if (connection == null) {
          log.error(jobLogAuditMessage(AUDIT_ACTION, projectId, jobId, "connectionRejected"))
          notify(CONNECTION_CHANGED_MESSAGE)
          return@background
        }
        download(projectId, jobId, connection, target)
        notify(RAW_LOG_SAVED_MESSAGE)
      } catch (e: CancellationException) {
        throw e
      } catch (e: GitLabApiException) {
        failed(e, "http", e.statusCode, e.correlationId)
      } catch (e: HttpTimeoutException) {
        failed(e, "timeout")
      } catch (e: IOException) {
        failed(e, "io")
      } catch (e: Exception) {
        // Same vocabulary as the Display Log audit; never the throwable itself.
        failed(e, "unexpected")
      }
    }
  }
}

/**
 * [JobTraceService.downloadRawTrace] that stops once the calling coroutine is cancelled (stopping
 * the plugin cancels it; closing the editor does not, see [JobLogSessionManager.launchSave]):
 * the copy stops at the next chunk (review M-4), and a read blocked on a stalled connection is
 * ended at once by closing the body (B1 review N-5), instead of waiting for the 10 minute deadline.
 * Either way it ends in `CancellationException`, the temporary file is removed and the target is
 * left as it was.
 */
internal suspend fun downloadRawTraceCancellably(
  service: JobTraceService,
  projectId: Long,
  jobId: Long,
  connection: ConnectionSnapshot,
  target: Path,
  io: CoroutineDispatcher = Dispatchers.IO,
) = coroutineScope {
  val closeBody = AtomicReference<() -> Unit>()
  // Cancellation does not reach a blocked read; this child closes the body when the save is cancelled.
  val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
    try {
      awaitCancellation()
    } finally {
      closeBody.get()?.invoke()
    }
  }
  try {
    withContext(io) {
      val onOpened = { close: () -> Unit ->
        closeBody.set(close)
        if (!isActive) close()
      }
      service.downloadRawTrace(projectId, jobId, connection, target, onOpened) { !isActive }
    }
  } catch (e: IOException) {
    // The body was closed under the read because the save was cancelled.
    if (!isActive) throw CancellationException("Raw job log download cancelled")
    throw e
  } finally {
    watcher.cancel()
  }
}

/**
 * UI thread. `setOverwrite(true)` lets the platform confirm replacing an existing file, so the
 * question is the native one. Null when the user cancels or no window is open.
 */
private fun promptForRawLogTarget(suggestedName: String): Path? {
  val shell = PlatformUI.getWorkbench().activeWorkbenchWindow?.shell ?: return null
  val dialog = FileDialog(shell, SWT.SAVE).apply {
    setText("Save Raw Job Log")
    setFileName(suggestedName)
    setOverwrite(true)
  }
  return dialog.open()?.let(Path::of)
}
