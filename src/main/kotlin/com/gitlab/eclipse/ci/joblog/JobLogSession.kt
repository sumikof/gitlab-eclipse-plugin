package com.gitlab.eclipse.ci.joblog

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.TraceFetch
import com.gitlab.eclipse.api.model.GitLabJob
import com.gitlab.eclipse.ci.CiAction
import com.gitlab.eclipse.ci.CiStatus
import com.gitlab.eclipse.ci.actions.CONNECTION_CHANGED_MESSAGE
import com.gitlab.eclipse.ci.actions.GENERIC_LOG_ERROR_MESSAGE
import com.gitlab.eclipse.ci.actions.LOG_NOT_FOUND_MESSAGE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.eclipse.core.runtime.ILog
import java.time.LocalTime
import java.util.Collections
import java.util.IdentityHashMap

private const val NANOS_PER_MILLI = 1_000_000L
private const val HTTP_FORBIDDEN = 403
private const val DISPLAY_ACTION = "displayJobLog"

/** A scheduled UI action that can still be called off (Display.timerExec in production). */
fun interface JobLogTimer {
  fun cancel()
}

/** Everything the session needs from the platform; production wiring in [JobLogSessionManager], fakes in tests. */
internal interface JobLogSessionEnv {
  /** UI thread. Runs [action] on the UI thread after [delayMs]. */
  fun schedule(delayMs: Long, action: () -> Unit): JobLogTimer

  /** Any thread. Never throws: once the display is gone the action is dropped. */
  fun runOnUi(action: () -> Unit)

  /** Runs [block] in the shared scope; the block must never let anything but cancellation escape. */
  fun launchIo(block: suspend () -> Unit): Job

  fun shouldAct(key: JobLogKey, generation: Long): Boolean

  fun nextGeneration(key: JobLogKey): Long

  fun nanoTime(): Long

  fun wallClock(): LocalTime

  /** UI thread. */
  fun notify(message: String)

  /** UI thread. A session's Retry state ([JobLogSession.canRetry]) may have changed. */
  fun retryStateChanged()
}

/** The GitLab calls of one poll. IO thread. */
internal interface JobLogRemote {
  fun pin(instanceUrl: String, authFingerprint: String): ConnectionSnapshot?

  fun trace(projectId: Long, jobId: Long, etag: String?, connection: ConnectionSnapshot): TraceFetch

  fun job(projectId: Long, jobId: Long, connection: ConnectionSnapshot): GitLabJob
}

/** One open editor for the session's key. UI thread. */
internal interface JobLogSessionView {
  val isDisposed: Boolean
  val isVisible: Boolean

  fun showPage(page: JobLogPage)

  /** The viewer the log goes to ([JobLogUiApplier]); null while the view has no control. */
  val surface: JobLogTextSurface?

  /**
   * Puts [runs] and [folds] on the document the view already shows: a duplicate tab opened after
   * the log was rendered shares the document (one per key) but has its own viewer.
   */
  fun decorate(runs: StyleRunIndex, folds: List<FoldRegion>)
}

/** What the editor shows instead of (or as) the log. */
sealed interface JobLogPage {
  data object Loading : JobLogPage

  /** A job that has not started, or a finished job without a log. */
  data class Waiting(val status: String?, val lastChecked: LocalTime?) : JobLogPage

  /** Polling stopped before any log could be shown; [message] says why. */
  data class Unavailable(val message: String) : JobLogPage

  data object Erased : JobLogPage

  data object Log : JobLogPage
}

/**
 * The live job log of one [JobLogKey] (design §5.3): UI-thread owned, shared by every editor for
 * the key. Each cycle is driven from the UI: a timer fires, one request runs in the background on
 * an immutable snapshot, and its result comes back to the UI where, in the same turn, it passes
 * the gate, updates the state, reaches the editors and schedules the next request. So at most one
 * request is ever in flight and a discarded result never has a successor.
 *
 * Stopping (finished job, 404, 401/403, changed connection) keeps the session and what the editors
 * show; [dispose] (last editor closed, plugin stopped) drops everything. The connection tags stay
 * private and never reach a log line. UI thread only.
 */
internal class JobLogSession(
  val key: JobLogKey,
  private val projectId: Long,
  private val jobId: Long,
  instanceUrl: String,
  authFingerprint: String,
  val content: JobLogContent,
  private val env: JobLogSessionEnv,
  remote: JobLogRemote,
  log: ILog,
) {
  private val audit = SessionAudit(log, projectId, jobId)
  private val views = SessionViews(audit)
  private val progress = TraceProgress()
  private val io = PollIo(remote, env, projectId, jobId, instanceUrl, authFingerprint)
  private val commands = Commands()
  private var generation = 0L
  private var timer: JobLogTimer? = null
  private var job: Job? = null
  private var disposed = false
  private var visible = true

  // The first log of a run opens at its end while the job is live.
  private var followNextFull = true

  // An append did not fit the document since the last full render; a finished job then reads its log once more.
  private var mismatched = false
  private var finalRefetched = false

  // The action of the request whose result is being committed, and its last failure (for the audit).
  private var action = DISPLAY_ACTION
  private var failure: PollFailure? = null

  internal var pollState = PollState()
    private set

  /** Bytes kept only to diff and continue the next poll: the compare window and the unfinished tail. */
  internal val retainedBytes: Long
    get() = progress.retainedBytes

  internal val hasViews: Boolean
    get() = !views.isEmpty

  /**
   * The job status the last status poll saw, null until one is seen in this run. Kept apart from
   * [pollState], whose status the policy drops when it stops (L4).
   */
  internal var lastStatus: String? = null
    private set

  private var retryInFlight = false

  // A retry of this job succeeded. Kept for the session's life, across start(): the old job stays
  // retried even when its log is shown again.
  private var retried = false

  /** Retry may be offered for this job now (same rule as the sidebar's Retry Job). */
  internal val canRetry: Boolean
    get() = jobLogCanRetry(disposed, lastStatus, retryInFlight, retried)

  /** A retry of this job was sent. */
  fun retryStarted() {
    if (disposed) return
    retryInFlight = true
    retryStateChanged()
  }

  /** The retry sent by [retryStarted] ended; only a [succeeded] one keeps Retry off. */
  fun retryFinished(succeeded: Boolean) {
    if (disposed) return
    retryInFlight = false
    if (succeeded) retried = true
    retryStateChanged()
  }

  init {
    content.page = JobLogPage.Loading
  }

  /**
   * (Re)starts polling from the job status with a new generation: earlier results are discarded
   * and the next trace is rendered from scratch. A log on screen stays until that render replaces it.
   */
  fun start() {
    if (disposed) return
    commands.cancelTimer()
    job?.cancel()
    job = null
    generation = env.nextGeneration(key)
    progress.release()
    seeStatus(null)
    if (content.page != JobLogPage.Log) commands.setPage(JobLogPage.Loading)
    visible = views.isEmpty || views.anyVisible()
    pollState = PollState()
    followNextFull = true
    mismatched = false
    finalRefetched = false
    step(PollEvent.Opened(visible))
  }

  /**
   * Adds [view]; a duplicate tab gets the colors and folds of the log already rendered. A visible
   * view resumes a paused session (its own partVisible may have come before its listener); a hidden
   * one never pauses it here, so a missed first partVisible cannot park the session for good.
   */
  fun attach(view: JobLogSessionView) {
    if (disposed || !views.add(view)) return
    if (progress.rendered) views.decorate(view, progress.runs, progress.folds.toList())
    if (!visible && view.isVisible) visibilityChanged()
  }

  /** Removes [view]. True when no view is left: the caller then disposes the session. */
  fun detach(view: JobLogSessionView): Boolean {
    views.remove(view)
    if (views.isEmpty) return true
    visibilityChanged()
    return false
  }

  /** A view was shown or hidden. Polling pauses only while no view of the key is visible. */
  fun visibilityChanged() {
    if (disposed) return
    val now = views.anyVisible()
    if (now == visible) return
    visible = now
    step(if (now) PollEvent.Visible else PollEvent.Hidden)
  }

  /** A button on the waiting page finished its write: check the status again right away. */
  fun actionDone() {
    if (!disposed) step(PollEvent.ActionDone)
  }

  /** Cancels everything and drops the log, its colors, folds and parser state. */
  fun dispose() {
    if (disposed) return
    disposed = true
    commands.cancelTimer()
    job?.cancel()
    job = null
    pollState = JobLogPollPolicy.step(pollState, PollEvent.Closed).first
    views.clear()
    progress.wipe()
    content.rendered = RenderResult.EMPTY
    content.page = JobLogPage.Log
  }

  private fun seeStatus(status: String?) {
    if (status == lastStatus) return
    lastStatus = status
    env.retryStateChanged()
  }

  // The page is sent again although it did not change: setPage skips an equal page, and a stopped
  // waiting page gets no other update, so this is how its Retry button follows the state.
  private fun retryStateChanged() {
    env.retryStateChanged()
    views.showPage(content.page)
  }

  private fun gate(gen: Long): Boolean =
    !disposed && gen == generation && env.shouldAct(key, gen) && pollState.phase != PollPhase.STOPPED

  private fun step(event: PollEvent) {
    val (next, out) = JobLogPollPolicy.step(pollState, event)
    pollState = next
    out.forEach(commands::execute)
  }

  private fun fire(request: PollRequest, gen: Long) {
    timer = null
    if (!gate(gen)) return
    val requestAction = actionFor(request, pollState.phase)
    step(PollEvent.Fired)
    val snapshot = progress.snapshot()
    job = env.launchIo {
      val outcome = io.poll(request, snapshot)
      env.runOnUi { if (gate(gen)) commit(requestAction, outcome) }
    }
  }

  private fun commit(requestAction: String, outcome: PollOutcome) {
    action = requestAction
    val onFailure = { f: PollFailure -> failure = f }
    val event = when (outcome) {
      is PollOutcome.Unpinned -> PollEvent.Failed(PollFailureKind.UNPINNED, outcome.elapsedMs)
      is PollOutcome.Status -> {
        (outcome.result as? StatusStepResult.Seen)?.let { seeStatus(it.status) }
        statusEvent(outcome.result, outcome.elapsedMs, onFailure)
      }
      is PollOutcome.Trace -> progress.event(outcome.result, outcome.elapsedMs, commands::applyUpdate, onFailure)
      is PollOutcome.Failure -> {
        onFailure(outcome.failure)
        PollEvent.Failed(outcome.failure.kind, outcome.elapsedMs)
      }
    }
    step(event)
  }

  /** Runs the policy's commands. Inner so that it shares the session's state. */
  private inner class Commands {
    fun execute(command: PollCommand) {
      when (command) {
        is PollCommand.FetchTrace -> schedule(command.delayMs, PollRequest.TRACE)
        is PollCommand.FetchStatus -> schedule(command.delayMs, PollRequest.STATUS)
        is PollCommand.FinalTrace -> schedule(command.delayMs, PollRequest.FINAL_TRACE)
        PollCommand.CancelScheduled -> cancelTimer()
        is PollCommand.ShowWaiting -> setPage(JobLogPage.Waiting(command.status, env.wallClock()))
        PollCommand.ShowLog -> setPage(JobLogPage.Log)
        PollCommand.ShowErased -> setPage(JobLogPage.Erased)
        is PollCommand.ReportFailure -> failure?.let { audit.failure(action, it) }
        PollCommand.ReportRecovered -> audit.recovered(action)
        is PollCommand.Stop -> stop(command.reason)
      }
    }

    fun cancelTimer() {
      timer?.cancel()
      timer = null
    }

    fun setPage(page: JobLogPage) {
      if (content.page == page) return
      content.page = page
      views.showPage(page)
    }

    // The document first; the page follows from the step after this one (ShowLog).
    fun applyUpdate(update: JobLogUpdate) {
      progress.record(update)
      val full = update is JobLogUpdate.Full
      if (update is JobLogUpdate.Full) content.rendered = update.result
      val follow = full && followNextFull && !pollState.stopAfterStart
      when (views.applyUpdate(update, follow)) {
        Applied.NONE -> Unit
        // One shared document per key: once it holds the text, later tabs connect to it and never read this.
        Applied.DONE -> if (full) {
          content.rendered = RenderResult.EMPTY
          followNextFull = false
          mismatched = false
        }
        // R2-4: the document is not the one the append continues; the next poll renders it all again.
        Applied.MISMATCH -> {
          progress.release()
          mismatched = true
          audit.line(action, "applyMismatch")
        }
      }
    }

    private fun schedule(delayMs: Long, request: PollRequest) {
      cancelTimer()
      val gen = generation
      timer = env.schedule(delayMs) { fire(request, gen) }
    }

    private fun stop(reason: StopReason) {
      cancelTimer()
      when (reason) {
        StopReason.TERMINAL -> {
          progress.finishing()?.let(::applyUpdate)
          if (mismatched) refetchFinal()
        }
        StopReason.NOT_FOUND -> stopWithNotice(LOG_NOT_FOUND_MESSAGE)
        // 403 never confirms that the job exists, the same as 404.
        StopReason.DENIED -> {
          val forbidden = failure?.httpStatus == HTTP_FORBIDDEN
          stopWithNotice(if (forbidden) LOG_NOT_FOUND_MESSAGE else GENERIC_LOG_ERROR_MESSAGE)
        }
        StopReason.UNPINNED -> {
          audit.line(action, "connectionRejected")
          showUnavailable(CONNECTION_CHANGED_MESSAGE)
        }
        StopReason.ERASED, StopReason.CLOSED -> Unit
      }
      // Nothing more will be appended: keep only what a new duplicate tab needs (runs and folds).
      if (reason != StopReason.CLOSED) progress.release()
    }

    /**
     * The last log of a finished job did not fit the document: read it once more from scratch so
     * that it does not stay broken. Outside the policy (already stopped), at most once per run;
     * another miss or a failure is only audited.
     */
    private fun refetchFinal() {
      if (finalRefetched) return
      finalRefetched = true
      mismatched = false
      val gen = generation
      val requestAction = action
      val snapshot = TraceSnapshot(etag = null, cursor = null, state = null, runs = StyleRunIndex.EMPTY)
      job = env.launchIo {
        val outcome = io.poll(PollRequest.FINAL_TRACE, snapshot)
        env.runOnUi {
          if (!disposed && gen == generation && env.shouldAct(key, gen)) commitFinal(requestAction, outcome)
        }
      }
    }

    private fun commitFinal(requestAction: String, outcome: PollOutcome) {
      action = requestAction
      when (outcome) {
        is PollOutcome.Trace ->
          progress.event(outcome.result, outcome.elapsedMs, ::applyUpdate) { audit.failure(action, it) }
        is PollOutcome.Failure -> audit.failure(action, outcome.failure)
        is PollOutcome.Unpinned -> audit.line(action, "connectionRejected")
        // A final trace never reads the status.
        is PollOutcome.Status -> Unit
      }
      progress.finishing()?.let(::applyUpdate)
      progress.release()
    }

    private fun stopWithNotice(message: String) {
      failure?.let { audit.failure(action, it) }
      showUnavailable(message)
    }

    private fun showUnavailable(message: String) {
      env.notify(message)
      val page = content.page
      if (page == JobLogPage.Loading || page is JobLogPage.Waiting) setPage(JobLogPage.Unavailable(message))
    }
  }
}

/** Background jobs that must not outlive the plugin (Save Raw Log); they may outlive their editors. UI thread. */
internal class SaveJobs {
  private val jobs = ArrayList<Job>()
  private var closed = false

  fun track(job: Job) {
    if (closed) {
      job.cancel()
      return
    }
    jobs.removeAll { it.isCompleted }
    jobs += job
  }

  fun cancelAll() {
    closed = true
    jobs.forEach { it.cancel() }
    jobs.clear()
  }
}

/** The outcome of one IO step, handed back to the UI thread. */
private sealed interface PollOutcome {
  val elapsedMs: Long

  class Trace(val result: TraceStepResult, override val elapsedMs: Long) : PollOutcome

  class Status(val result: StatusStepResult, override val elapsedMs: Long) : PollOutcome

  class Unpinned(override val elapsedMs: Long) : PollOutcome

  /** A failure outside the request itself (the pin, an unexpected exception), whatever was asked. */
  class Failure(val failure: PollFailure, override val elapsedMs: Long) : PollOutcome
}

/** The background half of a cycle: pin, request, classify. Holds the connection tags privately. */
private class PollIo(
  private val remote: JobLogRemote,
  private val env: JobLogSessionEnv,
  private val projectId: Long,
  private val jobId: Long,
  private val instanceUrl: String,
  private val authFingerprint: String,
) {
  /** IO thread. Never throws but [CancellationException]: nothing else may escape into the shared scope. */
  fun poll(request: PollRequest, snapshot: TraceSnapshot): PollOutcome {
    val t0 = env.nanoTime()
    return try {
      val connection = remote.pin(instanceUrl, authFingerprint) ?: return PollOutcome.Unpinned(elapsedSince(t0))
      if (request == PollRequest.STATUS) {
        PollOutcome.Status(statusStep { remote.job(projectId, jobId, connection) }, elapsedSince(t0))
      } else {
        val result = traceStep(snapshot, { tag -> remote.trace(projectId, jobId, tag, connection) })
        PollOutcome.Trace(result, elapsedSince(t0))
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // The class name is all that is kept: a message can carry the URL.
      val unexpected = PollFailure(PollFailureKind.TRANSIENT, "unexpected", exceptionClass = e.javaClass.simpleName)
      PollOutcome.Failure(unexpected, elapsedSince(t0))
    }
  }

  private fun elapsedSince(t0: Long): Long = (env.nanoTime() - t0) / NANOS_PER_MILLI
}

/** Where the next trace poll continues from, and what a new duplicate tab needs. UI thread. */
private class TraceProgress {
  private var etag: String? = null
  private var cursor: TraceCursor? = null
  private var state: RenderState? = null
  var runs = StyleRunIndex.EMPTY
    private set

  // Appended to in place: copying the list on every append is O(folds) on the UI thread.
  private val foldList = ArrayList<FoldRegion>()
  val folds: List<FoldRegion>
    get() = foldList

  /** Some log was put on the document since the session started. */
  var rendered = false
    private set

  val retainedBytes: Long
    get() = (cursor?.compareBytes?.size ?: 0).toLong() + (state?.pendingRaw?.size ?: 0)

  fun snapshot() = TraceSnapshot(etag, cursor, state, runs)

  /** Takes a trace result in and turns it into the policy's event; a changed log goes to [applyUpdate]. */
  fun event(
    result: TraceStepResult,
    elapsedMs: Long,
    applyUpdate: (JobLogUpdate) -> Unit,
    onFailure: (PollFailure) -> Unit,
  ): PollEvent = when (result) {
    is TraceStepResult.Changed -> {
      etag = result.etag
      cursor = result.cursor
      state = result.state
      applyUpdate(result.update)
      PollEvent.TraceChanged(elapsedMs, result.etag != null, result.totalBytes)
    }
    is TraceStepResult.Unchanged -> {
      etag = result.etag
      PollEvent.TraceUnchanged(elapsedMs, result.etag != null, result.totalBytes)
    }
    is TraceStepResult.Failed -> {
      onFailure(result.failure)
      PollEvent.Failed(result.failure.kind, elapsedMs)
    }
  }

  fun record(update: JobLogUpdate) {
    when (update) {
      is JobLogUpdate.Full -> {
        runs = update.result.runs
        foldList.clear()
        foldList.addAll(update.result.folds)
      }
      is JobLogUpdate.Append -> {
        runs = update.runs
        foldList.addAll(update.newFolds)
      }
    }
    rendered = true
  }

  /** The job finished, so no byte can continue the last line (or timestamped line group): confirm it. */
  fun finishing(): JobLogUpdate? {
    val pending = state ?: return null
    if (pending.pendingRaw.isEmpty()) return null
    val finish = finishRender(pending)
    state = finish.state
    val allRuns = runs.appendFrom(finish.replaceFrom, finish.runs)
    return JobLogUpdate.Append(finish.replaceFrom, finish.text, allRuns, finish.newFolds)
  }

  /** Drops what only the next poll needs; runs and folds stay for duplicate tabs. */
  fun release() {
    etag = null
    cursor = null
    state = null
  }

  fun wipe() {
    release()
    runs = StyleRunIndex.EMPTY
    foldList.clear()
    rendered = false
  }
}

/** What became of an update: no editor had a viewer, it is on screen, or it did not fit the document. */
private enum class Applied { NONE, DONE, MISMATCH }

/** The editors of a key, each called on its own so that one failing editor does not stop the others. UI thread. */
private class SessionViews(private val audit: SessionAudit) {
  private val views: MutableSet<JobLogSessionView> = Collections.newSetFromMap(IdentityHashMap())

  val isEmpty: Boolean
    get() = views.isEmpty()

  /** False when [view] was already attached (counted once). */
  fun add(view: JobLogSessionView): Boolean = views.add(view)

  fun remove(view: JobLogSessionView) {
    views.remove(view)
  }

  fun clear() = views.clear()

  fun anyVisible(): Boolean = views.any { !it.isDisposed && it.isVisible }

  fun showPage(page: JobLogPage) = each { it.showPage(page) }

  /** Puts [update] on every editor that has a viewer: each document is changed once ([JobLogUiApplier]). */
  fun applyUpdate(update: JobLogUpdate, followOnFull: Boolean): Applied {
    val surfaces = ArrayList<JobLogTextSurface>()
    each { view -> view.surface?.let(surfaces::add) }
    if (surfaces.isEmpty()) return Applied.NONE
    var applied = Applied.NONE
    audit.guarded {
      applied = if (JobLogUiApplier.apply(update, surfaces, followOnFull)) Applied.DONE else Applied.MISMATCH
    }
    return applied
  }

  fun decorate(view: JobLogSessionView, runs: StyleRunIndex, folds: List<FoldRegion>) {
    audit.guarded { view.decorate(runs, folds) }
  }

  private fun each(block: (JobLogSessionView) -> Unit) {
    views.filter { !it.isDisposed }.forEach { view -> audit.guarded { block(view) } }
  }
}

/** Audit lines of the session ([jobLogAuditMessage]): never a URL, a path, log text or a throwable. */
private class SessionAudit(private val log: ILog, private val projectId: Long, private val jobId: Long) {
  fun failure(action: String, failure: PollFailure) {
    log.error(
      jobLogAuditMessage(
        action,
        projectId,
        jobId,
        failure.outcome,
        failure.httpStatus,
        failure.correlationId,
        failure.exceptionClass,
      ),
    )
  }

  fun line(action: String, outcome: String) {
    log.error(jobLogAuditMessage(action, projectId, jobId, outcome))
  }

  fun recovered(action: String) {
    log.info(jobLogAuditMessage(action, projectId, jobId, "recovered"))
  }

  /** An editor that throws must neither stop the loop nor reach the platform's error handling with a throwable. */
  fun guarded(block: () -> Unit) {
    try {
      block()
    } catch (e: Exception) {
      val exceptionClass = e.javaClass.simpleName
      log.error(jobLogAuditMessage(DISPLAY_ACTION, projectId, jobId, "editorFailed", exceptionClass = exceptionClass))
    }
  }
}

/** Audit action of a request: the first status read and the first trace belong to Display Log itself. */
private fun actionFor(request: PollRequest, phase: PollPhase): String = when {
  request == PollRequest.STATUS && phase == PollPhase.LOADING -> DISPLAY_ACTION
  request == PollRequest.STATUS -> "pollJobStatus"
  phase == PollPhase.STARTING -> DISPLAY_ACTION
  else -> "pollJobLog"
}

private fun statusEvent(result: StatusStepResult, elapsedMs: Long, onFailure: (PollFailure) -> Unit): PollEvent =
  when (result) {
    is StatusStepResult.Seen -> PollEvent.StatusSeen(result.status, result.hasTrace, result.erased, elapsedMs)
    is StatusStepResult.Failed -> {
      onFailure(result.failure)
      PollEvent.Failed(result.failure.kind, elapsedMs)
    }
  }

/** Pure. Retry is offered on the same statuses as the sidebar's Retry Job ([CiStatus.contextAction]). */
internal fun jobLogCanRetry(disposed: Boolean, status: String?, inFlight: Boolean, retried: Boolean): Boolean =
  !disposed && !inFlight && !retried && CiStatus.contextAction(status) == CiAction.RETRYABLE
