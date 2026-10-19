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
import org.eclipse.ui.IWorkbenchWindow
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
  private val uiAvailable: () -> Boolean = ::workbenchCanShow,
) : MrDiffPorts {
  override fun findOpen(key: MrFileDiffKey): Boolean {
    val page = activePage() ?: return false
    return activateExisting(key, page)
  }

  /**
   * UI thread. Creates the Job, registers its cancel handle, and only then schedules it, so the
   * handle exists before the Job can finish. [onEndedWithoutRunning] fires if the Job is cancelled
   * before it ever ran (any thread).
   */
  override fun schedule(
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

  /**
   * UI thread. Nothing is opened while the workbench is closing or its display is gone (the result
   * is dropped; the controller has still released it). With no window left to show it in, the user
   * is told rather than left waiting.
   */
  override fun open(content: MrFileDiffContent) {
    if (!uiAvailable()) return
    val page = activePage()
    if (page == null) {
      show(MrDiffMessages.NO_WINDOW_MESSAGE)
      return
    }
    try {
      openOn(content, page)
    } catch (e: Exception) {
      val type = e.javaClass.name
      runCatching { logger<EclipseMrDiffPorts>().warn("MR file diff open failed: outcome=GENERIC exceptionType=$type") }
      show(MrDiffMessages.message(DiffFailure.GENERIC))
    }
  }

  /** Skipped while the workbench is closing or its display is gone. */
  override fun notify(message: String) {
    if (uiAvailable()) show(message)
  }
}

/**
 * The page to show a diff on: the active window's, or — when no window is active (the user switched
 * to another application while the diff loaded) — the first window that has a page.
 */
internal fun pageFor(active: IWorkbenchWindow?, windows: List<IWorkbenchWindow>): IWorkbenchPage? =
  active?.activePage ?: windows.firstNotNullOfOrNull { it.activePage }

/** UI thread. The page [pageFor] picks, or `null` without a running workbench or any window. */
private fun activeWorkbenchPage(): IWorkbenchPage? {
  if (!PlatformUI.isWorkbenchRunning()) return null
  val workbench = PlatformUI.getWorkbench()
  return pageFor(workbench.activeWorkbenchWindow, workbench.workbenchWindows.toList())
}

/** Whether a diff may be opened or a notification shown: a live display and a workbench that is not closing. */
private fun workbenchCanShow(): Boolean {
  if (!PlatformUI.isWorkbenchRunning()) return false
  val workbench = PlatformUI.getWorkbench()
  return !workbench.display.isDisposed && !workbench.isClosing
}

/** Any thread. Runs [block] on the UI thread later; see [hopOrRunInline] for when no display is left. */
private fun asyncOnLiveWorkbench(block: () -> Unit) {
  hopOrRunInline(liveAsyncExec(), block)
}

/** The live display's `asyncExec`, or `null` without a running workbench or with a disposed display. */
private fun liveAsyncExec(): ((Runnable) -> Unit)? {
  if (!PlatformUI.isWorkbenchRunning()) return null
  val display = PlatformUI.getWorkbench().display
  if (display.isDisposed) return null
  return { runnable -> display.asyncExec(runnable) }
}

/**
 * Posts [block] through [asyncExec]; the block itself always runs, because the controller's
 * delivery releases its generation in it (F1) — only opening and notifying are gated, in the ports.
 * With no display left to post to (null, or disposed while posting) it runs [block] right here: the
 * UI thread is gone, so nothing else can touch the UI-confined state any more, and the ports skip
 * every SWT call.
 */
internal fun hopOrRunInline(asyncExec: ((Runnable) -> Unit)?, block: () -> Unit) {
  if (asyncExec == null) {
    block()
    return
  }
  try {
    asyncExec(Runnable { block() })
  } catch (_: SWTException) {
    block()
  }
}
