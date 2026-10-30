package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.utils.debug
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.Status
import org.eclipse.core.runtime.jobs.Job
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** The name the install progress shows under in the Progress view (D-C1). */
internal const val INSTALL_PROGRESS_JOB_NAME = "Starting GitLab Language Server"

/** How long an install must run before its progress is shown, so a fast start shows nothing (D-C2). */
internal val DISPLAY_DELAY: Duration = 1000.milliseconds

/** How often the display Job re-reads the current phase while it waits for the session to close. */
internal const val POLL_MS = 100L

/** A step of the bundled language server install that is worth telling the user about. */
internal enum class InstallPhase { EXTRACTING, WAITING_FOR_LOCK, CLEANING_UP }

/** The sub task shown in the Progress view for [phase] (D-C1). */
internal fun installPhaseLabel(phase: InstallPhase): String = when (phase) {
  InstallPhase.EXTRACTING -> "Installing the bundled language server..."
  InstallPhase.WAITING_FOR_LOCK -> "Waiting for another Eclipse instance to finish installing the language server..."
  InstallPhase.CLEANING_UP -> "Removing old language server versions..."
}

/**
 * The progress of one install, as the installer reports it. Display only: nothing here can stop,
 * slow down or fail the install. Every call returns at once on any thread — it takes no lock and
 * never touches the Display, because the installer calls it while holding `lifecycleLock` — and
 * none of them throws.
 */
internal interface InstallProgressSession : AutoCloseable {
  /** Makes [phase] the current one. Ignored once the session is closed. */
  fun begin(phase: InstallPhase)

  /** Clears [phase] if it is still the current one; a phase begun after it is kept. */
  fun end(phase: InstallPhase)

  /** Ends the session and hides its display. Idempotent; also ends an open phase. Never throws. */
  override fun close()

  companion object {
    /** A session that shows nothing. */
    val NONE: InstallProgressSession = object : InstallProgressSession {
      override fun begin(phase: InstallPhase) = Unit

      override fun end(phase: InstallPhase) = Unit

      override fun close() = Unit
    }
  }
}

/** Opens one [InstallProgressSession] per install. */
internal fun interface InstallProgressReporter {
  fun open(): InstallProgressSession

  companion object {
    /** A reporter whose sessions show nothing. */
    val NONE: InstallProgressReporter = InstallProgressReporter { InstallProgressSession.NONE }
  }
}

/** Runs [block] as [phase]: a try/finally pair, so the phase never stays open past its block. */
internal inline fun <T> InstallProgressSession.during(phase: InstallPhase, block: () -> T): T {
  begin(phase)
  try {
    return block()
  } finally {
    end(phase)
  }
}

/**
 * What one session shares with its display Job. Atomics and a latch only: no lock, no Display.
 * [scheduled] records whether the display Job was ever handed to the Job manager.
 */
internal class InstallProgressState {
  val currentPhase = AtomicReference<InstallPhase?>(null)
  val closed = CountDownLatch(1)
  val scheduled = AtomicBoolean(false)
}

/**
 * The production reporter: each session shows a non-user Job in the Progress view, scheduled with
 * [delay] on the first phase and cancelled on close, so an install that ends within [delay] — normally
 * every start after the first — shows nothing. The Job only displays; cancelling it in the
 * Progress view hides it and leaves the install running. Every platform touch point is a
 * constructor seam, so this is tested without a Job manager.
 */
internal class JobInstallProgressReporter(
  delay: Duration = DISPLAY_DELAY,
  private val scheduleDisplay: (Job, Long) -> Unit = { job, ms -> job.schedule(ms) },
  private val cancelDisplay: (Job) -> Unit = { it.cancel() },
  private val newDisplayJob: (InstallProgressState) -> Job = ::InstallProgressJob,
) : InstallProgressReporter {
  private val delayMs = delay.inWholeMilliseconds

  /** Never throws: a display Job that cannot be made leaves the install without progress, not failed. */
  override fun open(): InstallProgressSession = runCatching {
    val state = InstallProgressState()
    JobSession(state, newDisplayJob(state))
  }.getOrElse {
    logFailure("create", it)
    InstallProgressSession.NONE
  }

  private fun logFailure(action: String, e: Throwable) {
    runCatching {
      logger<JobInstallProgressReporter>()
        .debug("Could not $action the language server install progress: ${e.javaClass.name}")
    }
  }

  private inner class JobSession(private val state: InstallProgressState, private val job: Job) :
    InstallProgressSession {
    private val closing = AtomicBoolean(false)

    override fun begin(phase: InstallPhase) {
      if (closing.get()) return
      state.currentPhase.set(phase)
      // A close that raced in between must not leave the phase behind.
      if (closing.get()) {
        state.currentPhase.set(null)
        return
      }
      if (state.scheduled.compareAndSet(false, true)) {
        // The Job manager rejects every schedule once the workbench is shutting down.
        runCatching { scheduleDisplay(job, delayMs) }.onFailure { logFailure("schedule", it) }
        // A close that ran between the check above and the schedule saw nothing to cancel, so the
        // Job just scheduled is cancelled here; a second cancel is harmless.
        if (closing.get()) cancel()
      }
    }

    override fun end(phase: InstallPhase) {
      state.currentPhase.compareAndSet(phase, null)
    }

    override fun close() {
      if (!closing.compareAndSet(false, true)) return
      state.currentPhase.set(null)
      state.closed.countDown()
      if (state.scheduled.get()) cancel()
    }

    private fun cancel() {
      runCatching { cancelDisplay(job) }.onFailure { logFailure("cancel", it) }
    }
  }
}

/**
 * The display Job body: shows the current phase as a sub task until the session closes. A cancel
 * from the Progress view reports CANCEL, which only hides the display. Anything that escapes —
 * an interrupt included — is reported OK, so the Job manager never raises an error dialog.
 */
internal fun runInstallProgressDisplay(
  state: InstallProgressState,
  monitor: IProgressMonitor,
  pollMillis: Long = POLL_MS,
): IStatus = try {
  monitor.beginTask(INSTALL_PROGRESS_JOB_NAME, IProgressMonitor.UNKNOWN)
  var last: InstallPhase? = null
  var status: IStatus = Status.OK_STATUS
  while (!state.closed.await(pollMillis, TimeUnit.MILLISECONDS)) {
    if (monitor.isCanceled) {
      status = Status.CANCEL_STATUS
      break
    }
    val phase = state.currentPhase.get()
    if (phase != null && phase != last) {
      monitor.subTask(installPhaseLabel(phase))
      last = phase
    }
  }
  status
} catch (_: Throwable) {
  Status.OK_STATUS
} finally {
  runCatching { monitor.done() }
}

/** The family of every install progress Job, so the bundle stop can cancel them all. */
internal object InstallProgressJobs {
  val FAMILY: Any = Any()

  fun cancelAll() {
    Job.getJobManager().cancel(FAMILY)
  }
}

/** The display Job: shown in the Progress view and the status bar, never as a user dialog. */
internal class InstallProgressJob(private val state: InstallProgressState) : Job(INSTALL_PROGRESS_JOB_NAME) {
  init {
    isUser = false
    isSystem = false
  }

  override fun belongsTo(family: Any?): Boolean = family === InstallProgressJobs.FAMILY

  override fun run(monitor: IProgressMonitor): IStatus = runInstallProgressDisplay(state, monitor)
}
