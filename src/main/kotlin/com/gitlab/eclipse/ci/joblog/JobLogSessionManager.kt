package com.gitlab.eclipse.ci.joblog

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.JobService
import com.gitlab.eclipse.api.JobTraceService
import com.gitlab.eclipse.api.TraceFetch
import com.gitlab.eclipse.api.model.GitLabJob
import com.gitlab.eclipse.ci.actions.GENERIC_LOG_ERROR_MESSAGE
import com.gitlab.eclipse.ci.actions.pinnedConnectionFor
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.eclipse.core.runtime.ILog
import org.eclipse.swt.SWTException
import java.time.LocalTime

/**
 * Which job to show: resolved on the UI thread from the sidebar node. Not a data class: it carries
 * the connection tags, which must never reach a `toString` or a log line.
 */
class JobLogTarget(
  val key: JobLogKey,
  val projectId: Long,
  val jobId: Long,
  internal val instanceUrl: String,
  internal val authFingerprint: String,
)

/**
 * The sessions by key (one per job log) and their lifetime: a session lives while at least one
 * editor is attached. UI thread only. [JobLogSessionManager] holds the production instance.
 */
internal class JobLogSessionRegistry(
  private val env: JobLogSessionEnv,
  private val remote: JobLogRemote,
  private val log: ILog,
) {
  private val sessions = HashMap<JobLogKey, JobLogSession>()

  // Save Raw Log downloads outlive the editor that started them; only stopping the plugin ends them.
  private val saves = SaveJobs()

  /**
   * Starts (or restarts) the session of [target] and asks [show] to bring an editor for it up.
   * [show] opens or activates the editor synchronously, which attaches it; a session that ends the
   * call without any editor (opening failed or nothing opened) is dropped at once.
   */
  fun open(target: JobLogTarget, show: (JobLogEditorInput) -> Unit) {
    val session = sessions.getOrPut(target.key) {
      JobLogSession(
        target.key,
        target.projectId,
        target.jobId,
        target.instanceUrl,
        target.authFingerprint,
        JobLogContent(RenderResult.EMPTY),
        env,
        remote,
        log,
      )
    }
    // The page is on the content before the editor's doSetInput reads it.
    session.start()
    try {
      show(JobLogEditorInput(target.key, session.content, target.instanceUrl, target.authFingerprint))
    } catch (e: Exception) {
      val exceptionClass = e.javaClass.simpleName
      val (projectId, jobId) = target.projectId to target.jobId
      log.error(jobLogAuditMessage("displayJobLog", projectId, jobId, "editorFailed", exceptionClass = exceptionClass))
      env.notify(GENERIC_LOG_ERROR_MESSAGE)
    }
    if (!session.hasViews) discard(target.key)
  }

  fun attach(key: JobLogKey, view: JobLogSessionView) {
    sessions[key]?.attach(view)
  }

  fun detach(key: JobLogKey, view: JobLogSessionView) {
    if (sessions[key]?.detach(view) == true) discard(key)
  }

  fun visibilityChanged(key: JobLogKey) {
    sessions[key]?.visibilityChanged()
  }

  fun actionDone(key: JobLogKey) {
    sessions[key]?.actionDone()
  }

  /**
   * Runs a Save Raw Log download of [key] in the background. Saving starts only from an open job
   * log, so without a session nothing starts. Closing the key's last editor lets the download finish
   * and notify as usual; stopping the plugin cancels it ([disposeAll]).
   */
  fun launchSave(key: JobLogKey, block: suspend () -> Unit) {
    if (sessions[key] == null) return
    saves.track(env.launchIo(block))
  }

  /** Plugin stop: every session and every running save is dropped, whether or not its editors get closed. */
  fun disposeAll() {
    saves.cancelAll()
    val all = sessions.values.toList()
    sessions.clear()
    all.forEach(JobLogSession::dispose)
  }

  fun sessionFor(key: JobLogKey): JobLogSession? = sessions[key]

  private fun discard(key: JobLogKey) {
    sessions.remove(key)?.dispose()
  }
}

/**
 * The production [JobLogSessionRegistry]. A singleton `object` for the same reason as
 * [JobLogEditorOpener]: no start-time wiring in `GitLabEclipseStartup`. UI thread only.
 */
internal object JobLogSessionManager {
  private val registry by lazy {
    JobLogSessionRegistry(DisplaySessionEnv, ApiJobLogRemote(), logger<JobLogSessionManager>())
  }

  fun open(target: JobLogTarget, show: (JobLogEditorInput) -> Unit) = registry.open(target, show)

  fun attach(key: JobLogKey, view: JobLogSessionView) = registry.attach(key, view)

  fun detach(key: JobLogKey, view: JobLogSessionView) = registry.detach(key, view)

  fun visibilityChanged(key: JobLogKey) = registry.visibilityChanged(key)

  fun actionDone(key: JobLogKey) = registry.actionDone(key)

  fun launchSave(key: JobLogKey, block: suspend () -> Unit) = registry.launchSave(key, block)

  fun disposeAll() = registry.disposeAll()
}

/** SWT timers and hops, the plugin's shared scope and the Display Log generations. */
private object DisplaySessionEnv : JobLogSessionEnv {
  private val scope by lazyService<CoroutineScope>()

  override fun schedule(delayMs: Long, action: () -> Unit): JobLogTimer {
    val display = currentDisplay
    val runnable = Runnable { action() }
    try {
      display.timerExec(delayMs.toInt(), runnable)
    } catch (ignored: SWTException) {
      // Display gone: nothing will run, so there is nothing to cancel either.
    }
    return JobLogTimer {
      try {
        if (!display.isDisposed) display.timerExec(-1, runnable)
      } catch (ignored: SWTException) {
        // Display gone: the timer died with it.
      }
    }
  }

  override fun runOnUi(action: () -> Unit) {
    try {
      currentDisplay.asyncExec {
        try {
          if (!currentDisplay.isDisposed) action()
        } catch (ignored: SWTException) {
          // Display disposed mid-turn.
        }
      }
    } catch (ignored: SWTException) {
      // asyncExec on a disposed display: never let it cancel the shared scope.
    } catch (ignored: IllegalStateException) {
      // Workbench torn down (display lookup off the UI thread).
    }
  }

  override fun launchIo(block: suspend () -> Unit): Job = scope.launch { block() }

  override fun shouldAct(key: JobLogKey, generation: Long): Boolean =
    JobLogGenerationRegistry.shouldAct(key, generation)

  override fun nextGeneration(key: JobLogKey): Long = JobLogGenerationRegistry.nextGeneration(key)

  override fun nanoTime(): Long = System.nanoTime()

  override fun wallClock(): LocalTime = LocalTime.now()

  override fun notify(message: String) = NotificationUtils.showOnUiThread(message)
}

/** The REST calls, each pinned to the connection the sidebar node came from (design B7). */
private class ApiJobLogRemote : JobLogRemote {
  private val apiClient by lazyService<GitLabApiClient>()

  override fun pin(instanceUrl: String, authFingerprint: String): ConnectionSnapshot? =
    pinnedConnectionFor(apiClient, instanceUrl, authFingerprint)

  override fun trace(projectId: Long, jobId: Long, etag: String?, connection: ConnectionSnapshot): TraceFetch =
    JobTraceService(apiClient).getTraceSnapshot(projectId, jobId, etag, connection)

  override fun job(projectId: Long, jobId: Long, connection: ConnectionSnapshot): GitLabJob =
    JobService(apiClient).getJob(projectId, jobId, connection)
}
