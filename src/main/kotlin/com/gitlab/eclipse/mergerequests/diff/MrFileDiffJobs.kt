package com.gitlab.eclipse.mergerequests.diff

import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.OperationCanceledException
import org.eclipse.core.runtime.Status
import org.eclipse.core.runtime.jobs.IJobChangeEvent
import org.eclipse.core.runtime.jobs.Job
import org.eclipse.core.runtime.jobs.JobChangeAdapter
import org.eclipse.swt.SWTException
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.ui.PlatformUI
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** What a diff Job runs: the controller's work, given its cancellation check. */
internal typealias DiffWork = (isCancelled: () -> Boolean) -> Unit

/**
 * The background Jobs that load MR file diffs (design §7): one family, so the bundle stop can
 * cancel them all; no scheduling rule; not user jobs (no progress dialog), but shown in the
 * Progress view and cancellable there.
 */
object MrFileDiffJobs {
  /** The family every MR file diff Job belongs to. */
  val FAMILY: Any = Any()

  /**
   * A Job named [name] that runs [work] through [runWork]. [onEndedWithoutRunning] is called, on
   * whichever thread ends the Job, when it ends without its body ever having run — the case of a
   * Job cancelled while it was still waiting, for which Eclipse never calls `run`.
   */
  internal fun newJob(name: String, work: DiffWork, onEndedWithoutRunning: () -> Unit): Job {
    val job = MrFileDiffJob(name, work)
    job.isUser = false
    job.addJobChangeListener(
      object : JobChangeAdapter() {
        override fun done(event: IJobChangeEvent) {
          if (!job.started.get()) onEndedWithoutRunning()
        }
      },
    )
    return job
  }

  /** Bundle stop: cancels every waiting or running MR file diff Job. */
  fun cancelAll() {
    Job.getJobManager().cancel(FAMILY)
  }

  /**
   * The Job body. Cancellation reports CANCEL; anything else that escapes [work] — which should
   * not happen, the loader folds its failures — is logged as its type only and reported OK, so
   * nothing reaches the job manager (which would raise an error dialog).
   */
  internal fun runWork(work: DiffWork, monitor: IProgressMonitor?): IStatus = try {
    work { monitor?.isCanceled == true }
    Status.OK_STATUS
  } catch (_: OperationCanceledException) {
    Status.CANCEL_STATUS
  } catch (_: CancellationException) {
    Status.CANCEL_STATUS
  } catch (e: Throwable) {
    runCatching {
      logger<MrFileDiffJobs>().warn("MR file diff job failed: outcome=GENERIC exceptionType=${e.javaClass.name}")
    }
    Status.OK_STATUS
  }

  private class MrFileDiffJob(name: String, private val work: DiffWork) : Job(name) {
    val started = AtomicBoolean(false)

    override fun belongsTo(family: Any?): Boolean = family === FAMILY

    override fun run(monitor: IProgressMonitor?): IStatus {
      started.set(true)
      return runWork(work, monitor)
    }
  }
}

/**
 * The production [MrDiffPorts]: Eclipse Jobs, the workbench display, the active page and
 * [MrCompareOpener]. Every platform touch point is a constructor seam, so this adapter is tested
 * without a Display or a workbench.
 */
internal class EclipseMrDiffPorts(
  private val activePage: () -> IWorkbenchPage? = ::activeWorkbenchPage,
  private val hop: (() -> Unit) -> Unit = ::asyncOnLiveWorkbench,
  private val activateExisting: (MrFileDiffKey, IWorkbenchPage) -> Boolean = MrCompareOpener::activateExisting,
  private val openOn: (MrFileDiffContent, IWorkbenchPage) -> Unit = MrCompareOpener::open,
  private val show: (String) -> Unit = { NotificationUtils.show(it) },
  private val newJob: (String, DiffWork, () -> Unit) -> Job = MrFileDiffJobs::newJob,
) : MrDiffPorts {
  override fun findOpen(key: MrFileDiffKey): Boolean {
    val page = activePage() ?: return false
    return activateExisting(key, page)
  }

  override fun schedule(name: String, work: DiffWork, onCancelHandle: (() -> Unit) -> Unit) =
    schedule(name, work, onCancelHandle) {}

  /**
   * UI thread. Creates the Job, registers its cancel handle, and only then schedules it, so the
   * handle exists before the Job can finish. [onEndedWithoutRunning] fires if the Job is cancelled
   * before it ever ran (any thread); [MrDiffPorts.schedule] cannot pass one yet and uses a no-op.
   */
  fun schedule(
    name: String,
    work: DiffWork,
    onCancelHandle: (() -> Unit) -> Unit,
    onEndedWithoutRunning: () -> Unit,
  ) {
    val job = newJob(name, work, onEndedWithoutRunning)
    job.isUser = false
    onCancelHandle { job.cancel() }
    job.schedule()
  }

  override fun uiHop(block: () -> Unit) = hop(block)

  /** UI thread. The window may have gone while loading; then the result is dropped. */
  override fun open(content: MrFileDiffContent) {
    val page = activePage() ?: return
    try {
      openOn(content, page)
    } catch (e: Exception) {
      val type = e.javaClass.name
      runCatching { logger<EclipseMrDiffPorts>().warn("MR file diff open failed: outcome=GENERIC exceptionType=$type") }
      show(MrDiffMessages.message(DiffFailure.GENERIC))
    }
  }

  override fun notify(message: String) = show(message)
}

/** UI thread. The active window's active page, or `null` without a running workbench or window. */
private fun activeWorkbenchPage(): IWorkbenchPage? {
  if (!PlatformUI.isWorkbenchRunning()) return null
  return PlatformUI.getWorkbench().activeWorkbenchWindow?.activePage
}

/** Any thread. Runs [block] on the UI thread later, unless the display is gone or the workbench is closing. */
private fun asyncOnLiveWorkbench(block: () -> Unit) {
  if (!PlatformUI.isWorkbenchRunning()) return
  val workbench = PlatformUI.getWorkbench()
  val display = workbench.display
  if (display.isDisposed) return
  try {
    display.asyncExec { if (!display.isDisposed && !workbench.isClosing) block() }
  } catch (_: SWTException) {
    // Disposed between the check and the scheduling: nothing left to show the result in.
  }
}
