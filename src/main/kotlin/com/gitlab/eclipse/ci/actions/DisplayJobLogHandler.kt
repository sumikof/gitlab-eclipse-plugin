package com.gitlab.eclipse.ci.actions

import com.gitlab.eclipse.ci.joblog.JobLogEditorOpener
import com.gitlab.eclipse.ci.joblog.JobLogKey
import com.gitlab.eclipse.ci.joblog.JobLogSessionManager
import com.gitlab.eclipse.ci.joblog.JobLogTarget
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.views.sidebar.JobNode
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent

internal const val SELECT_JOB_MESSAGE = "Select a job in the GitLab sidebar first."
internal const val NO_PROJECT_MESSAGE = "Couldn't determine the job's project; refresh the sidebar."

/** Non-committal on purpose (design §15): 403 is hidden as 404, so never confirm existence. */
internal const val LOG_NOT_FOUND_MESSAGE = "The log doesn't exist or you don't have access to it."
internal const val GENERIC_LOG_ERROR_MESSAGE = "Couldn't load the job log. Try again."

/**
 * Opens the log of the job selected in the GitLab sidebar in the folding, colored job-log editor
 * ([com.gitlab.eclipse.ci.joblog.JobLogEditor], design B §5.3) and follows it while the job runs.
 * The selection is resolved on the UI thread; the job's session ([JobLogSessionManager]) numbers
 * the run, opens the editor at once (loading page) and does every request in the background,
 * pinned to the connection the node came from. Running it again restarts the session (L-B2-3).
 * There is NO [InFlightWriteGuard]: reads are idempotent and only the latest run may show.
 */
@Suppress("unused")
class DisplayJobLogHandler(
  private val open: (JobLogTarget) -> Unit = DEFAULT_OPEN_JOB_LOG,
  private val notify: (String) -> Unit = DEFAULT_NOTIFY_JOB_LOG,
) : AbstractHandler() {
  override fun execute(event: ExecutionEvent): Any? {
    displayJobLog(selectedSidebarNode<JobNode>(event), notify, open)
    return null
  }
}

/** Display Log's production opener: the job's session shows it in the job-log editor. UI thread. */
internal val DEFAULT_OPEN_JOB_LOG: (JobLogTarget) -> Unit =
  { target -> JobLogSessionManager.open(target, JobLogEditorOpener::show) }

/** Display Log's production notifier. */
internal val DEFAULT_NOTIFY_JOB_LOG: (String) -> Unit = { NotificationUtils.show(it) }

/** UI thread. The Display Log entry shared by [DisplayJobLogHandler] and the sidebar double-click (L6). */
internal fun displayJobLogInEditor(node: JobNode?) = displayJobLog(node, DEFAULT_NOTIFY_JOB_LOG, DEFAULT_OPEN_JOB_LOG)

/** UI thread. Resolves the selected [node] to the job to show, or tells the user what is missing. */
internal fun displayJobLog(node: JobNode?, notify: (String) -> Unit, open: (JobLogTarget) -> Unit) {
  if (node == null) {
    notify(SELECT_JOB_MESSAGE)
    return
  }
  val projectId = node.projectId
  if (projectId == null) {
    notify(NO_PROJECT_MESSAGE)
    return
  }
  val jobId = node.job.id
  val key = JobLogKey.of(node.sourceInstanceUrl, node.sourceAuthFingerprint, projectId, jobId)
  open(JobLogTarget(key, projectId, jobId, node.sourceInstanceUrl, node.sourceAuthFingerprint))
}
