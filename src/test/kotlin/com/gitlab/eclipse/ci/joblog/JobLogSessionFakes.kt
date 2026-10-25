package com.gitlab.eclipse.ci.joblog

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.TraceFetch
import com.gitlab.eclipse.api.model.GitLabJob
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.eclipse.core.runtime.ILog
import org.eclipse.jface.text.Document
import org.eclipse.jface.text.IDocument
import java.io.IOException
import java.time.LocalTime

internal const val FAKE_URL = "https://gitlab.example.com"
internal const val FAKE_FP = "fingerprint-secret"
internal const val FAKE_PROJECT = 42L
internal const val FAKE_JOB = 7L
internal const val STARTED = "2024-01-01T00:00:00Z"
internal const val TEN_MINUTES = 600_000L

internal val FAKE_KEY = JobLogKey.of(FAKE_URL, FAKE_FP, FAKE_PROJECT, FAKE_JOB)

internal val FAKE_SNAPSHOT = ConnectionSnapshot(
  instanceUrl = FAKE_URL,
  token = "token-value",
  authFingerprint = FAKE_FP,
  configGeneration = 1L,
)

/** The audit line of the fake job ([FAKE_PROJECT] / [FAKE_JOB]). */
internal fun auditLine(action: String, tail: String) = "ciReadAction action=$action projectId=42 jobId=7 $tail"

internal fun fakeTarget() = JobLogTarget(FAKE_KEY, FAKE_PROJECT, FAKE_JOB, FAKE_URL, FAKE_FP)

internal fun job(status: String?, startedAt: String? = null, erasedAt: String? = null) =
  GitLabJob(id = FAKE_JOB, status = status, startedAt = startedAt, erasedAt = erasedAt)

internal fun body(text: String, etag: String? = null, omitted: Long = 0): TraceFetch.Body =
  text.toByteArray().let { TraceFetch.Body(it, etag = etag, totalBytes = it.size + omitted, omittedBytes = omitted) }

/**
 * Platform fake: a virtual clock with manual timers, a UI queue the test drains, and IO that runs
 * at once on an unconfined dispatcher (so a fired request has its result queued, not applied,
 * until [drainUi]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class FakeEnv : JobLogSessionEnv {
  inner class FakeTimer(val due: Long, val delayMs: Long, val action: () -> Unit) : JobLogTimer {
    var cancelled = false
    var fired = false

    override fun cancel() {
      cancelled = true
    }
  }

  private val scope = CoroutineScope(UnconfinedTestDispatcher())
  private var counter = 0L
  private val latest = HashMap<JobLogKey, Long>()
  var now = 0L
  var nano = 0L
  var active = true
  val timers = mutableListOf<FakeTimer>()
  val ui = ArrayDeque<() -> Unit>()
  val notices = mutableListOf<String>()

  val pending: List<FakeTimer>
    get() = timers.filter { !it.cancelled && !it.fired }

  /** The most timers armed at once (the policy keeps stale results; the session must keep this at 1). */
  var maxArmed = 0

  override fun schedule(delayMs: Long, action: () -> Unit): JobLogTimer =
    FakeTimer(now + delayMs, delayMs, action).also {
      timers += it
      maxArmed = maxOf(maxArmed, pending.size)
    }

  override fun runOnUi(action: () -> Unit) {
    ui += action
  }

  /** Every job launched, in order. */
  val launched = mutableListOf<Job>()

  override fun launchIo(block: suspend () -> Unit): Job = scope.launch { block() }.also { launched += it }

  override fun shouldAct(key: JobLogKey, generation: Long): Boolean = active && latest[key] == generation

  override fun nextGeneration(key: JobLogKey): Long = (++counter).also { latest[key] = it }

  override fun nanoTime(): Long = nano

  override fun wallClock(): LocalTime = LocalTime.of(10, 0, 0)

  override fun notify(message: String) {
    notices += message
  }

  fun drainUi() {
    while (ui.isNotEmpty()) ui.removeFirst().invoke()
  }

  /** Fires the earliest pending timer due by now + [withinMs] without delivering its result. */
  fun fireNext(withinMs: Long = TEN_MINUTES): Boolean {
    val next = pending.filter { it.due <= now + withinMs }.minByOrNull { it.due } ?: return false
    now = next.due
    next.fired = true
    next.action()
    return true
  }

  /** Moves the clock by [ms], firing every timer that falls due and delivering each result. */
  fun advance(ms: Long) {
    val target = now + ms
    while (true) {
      val next = pending.filter { it.due <= target }.minByOrNull { it.due } ?: break
      now = next.due
      next.fired = true
      next.action()
      drainUi()
    }
    now = target
  }
}

/** Scripted GitLab: one reply per call, in order. Records the calls and any overlap between requests. */
internal class FakeRemote(private val env: FakeEnv) : JobLogRemote {
  val jobReplies = ArrayDeque<() -> GitLabJob>()
  val traceReplies = ArrayDeque<() -> TraceFetch>()
  val requests = mutableListOf<String>()
  var pinResult: ConnectionSnapshot? = FAKE_SNAPSHOT
  var callNanos = 0L

  /** Requests sent while an earlier result was still on its way to the UI (must stay 0). */
  var overlaps = 0

  override fun pin(instanceUrl: String, authFingerprint: String): ConnectionSnapshot? = pinResult

  override fun trace(projectId: Long, jobId: Long, etag: String?, connection: ConnectionSnapshot): TraceFetch {
    record("trace etag=$etag")
    return (traceReplies.removeFirstOrNull() ?: { throw IOException("no scripted trace") }).invoke()
  }

  override fun job(projectId: Long, jobId: Long, connection: ConnectionSnapshot): GitLabJob {
    record("job")
    return (jobReplies.removeFirstOrNull() ?: { throw IOException("no scripted job") }).invoke()
  }

  fun jobs(vararg replies: GitLabJob) = replies.forEach { reply -> jobReplies += { reply } }

  fun traces(vararg replies: TraceFetch) = replies.forEach { reply -> traceReplies += { reply } }

  private fun record(request: String) {
    if (env.ui.isNotEmpty()) overlaps++
    requests += request
    env.nano += callNanos
  }
}

/**
 * An editor: visibility and disposal are switchable; pages and updates are recorded ("full" when a
 * Full is decorated, "append:<replaceFrom>" when an Append swaps the runs). Each view has its own
 * [document] unless one is passed in (the shared document of L-B2-1 (A)).
 */
internal class FakeView(
  var visible: Boolean = true,
  val document: IDocument = Document(),
) : JobLogSessionView {
  var disposed = false
  var tailVisible = true
  var reveals = 0
  val calls = mutableListOf<String>()

  /** False: the editor has no viewer yet, so no update reaches it. */
  var hasSurface = true

  /** True: [showPage] throws, as a broken editor would. */
  var failPages = false

  override val surface: JobLogTextSurface?
    get() = if (hasSurface) fakeSurface else null

  private val fakeSurface = object : JobLogTextSurface {
    override val document: IDocument
      get() = this@FakeView.document

    override var topIndex = 0

    override fun isTailVisible() = tailVisible

    override fun swapRuns(runs: StyleRunIndex, from: Int) {
      calls += "append:$from"
    }

    override fun refreshVisible() = Unit

    override fun addFolds(folds: List<FoldRegion>) = Unit

    override fun applyRendered(result: RenderResult) {
      calls += "full"
    }

    override fun revealEnd() {
      reveals++
    }
  }

  override val isDisposed: Boolean
    get() = disposed

  override val isVisible: Boolean
    get() = visible

  override fun showPage(page: JobLogPage) {
    check(!failPages) { "broken editor" }
    calls += "page:" + when (page) {
      JobLogPage.Loading -> "Loading"
      is JobLogPage.Waiting -> "Waiting(${page.status})"
      is JobLogPage.Unavailable -> "Unavailable(${page.message})"
      JobLogPage.Erased -> "Erased"
      JobLogPage.Log -> "Log"
    }
  }

  override fun decorate(runs: StyleRunIndex, folds: List<FoldRegion>) {
    calls += "decorate:${runs.size}:${folds.size}"
  }
}

/** One registry with fakes around it; [open] stands for Display Log with an editor that attaches [views]. */
internal class FakeWorld {
  val env = FakeEnv()
  val remote = FakeRemote(env)
  val log = mockk<ILog>(relaxUnitFun = true)
  val errors = mutableListOf<String>()
  val infos = mutableListOf<String>()
  val registry = JobLogSessionRegistry(env, remote, log)

  init {
    every { log.error(capture(errors)) } just Runs
    every { log.info(capture(infos)) } just Runs
  }

  val session: JobLogSession?
    get() = registry.sessionFor(FAKE_KEY)

  fun open(vararg views: FakeView) {
    registry.open(fakeTarget()) { input -> views.forEach { registry.attach(input.key, it) } }
  }

  fun checkInvariants() {
    remote.overlaps shouldBe 0
    (env.maxArmed <= 1) shouldBe true
    verify(exactly = 0) { log.error(any(), any<Throwable>()) }
    verify(exactly = 0) { log.warn(any(), any<Throwable>()) }
    verify(exactly = 0) { log.info(any(), any<Throwable>()) }
    (errors + infos).forEach { line ->
      line shouldNotContain "https://"
      line shouldNotContain "gitlab.example.com"
      line shouldNotContain FAKE_FP
      line shouldNotContain "token-value"
    }
  }
}
