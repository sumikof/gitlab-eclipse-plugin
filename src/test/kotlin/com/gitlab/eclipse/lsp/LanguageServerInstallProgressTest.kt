package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.utils.DebugLogging
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.MockKMatcherScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.Platform
import org.eclipse.core.runtime.Status
import org.eclipse.core.runtime.jobs.IJobChangeEvent
import org.eclipse.core.runtime.jobs.Job
import org.eclipse.core.runtime.jobs.JobChangeAdapter
import org.osgi.framework.Bundle
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

private const val WAIT_SECONDS = 10L

/** A delay long enough that a scheduled Job never starts during the test (it is cancelled first). */
private val NEVER_MS = TimeUnit.HOURS.toMillis(1)

/** A [JobInstallProgressReporter] whose display seams are recorded instead of reaching a Job manager. */
private class FakeDisplay(
  private val scheduleFailure: Throwable? = null,
  private val cancelFailure: Throwable? = null,
) {
  val scheduled = mutableListOf<Pair<Job, Long>>()
  val cancelled = mutableListOf<Job>()
  val states = mutableListOf<InstallProgressState>()
  val job: Job = mockk(relaxed = true)

  fun reporter(delayMs: Long = 1_000L) = JobInstallProgressReporter(
    delay = delayMs.milliseconds,
    scheduleDisplay = { job, ms ->
      scheduled += job to ms
      scheduleFailure?.let { throw it }
    },
    cancelDisplay = { job ->
      cancelled += job
      cancelFailure?.let { throw it }
    },
    newDisplayJob = { state ->
      states += state
      job
    },
  )
}

private fun relaxedMonitor(cancelled: Boolean = false): IProgressMonitor =
  mockk(relaxed = true) { every { isCanceled } returns cancelled }

/** Runs [block] on a fresh thread and returns its result as a future. */
private fun <T> onThread(block: () -> T): CompletableFuture<T> {
  val future = CompletableFuture<T>()
  Thread {
    try {
      future.complete(block())
    } catch (e: Throwable) {
      future.completeExceptionally(e)
    }
  }.apply { isDaemon = true }.start()
  return future
}

/** A latch released the first time [call] is made on this relaxed mock; it still answers like a relaxed call. */
private fun IProgressMonitor.latchOn(call: MockKMatcherScope.(IProgressMonitor) -> Unit): CountDownLatch {
  val latch = CountDownLatch(1)
  val monitor = this
  every { call(monitor) } answers { latch.countDown() }
  return latch
}

/**
 * Runs the display body on another thread while [steer] drives it from the test thread. Every wait
 * is bounded, and the session is always closed at the end, so a broken body fails the test instead
 * of hanging the test worker.
 */
private suspend fun displayOnThread(
  state: InstallProgressState,
  monitor: IProgressMonitor,
  steer: suspend () -> Unit,
): IStatus {
  val future = onThread { runInstallProgressDisplay(state, monitor, pollMillis = 10L) }
  try {
    steer()
    return future.get(WAIT_SECONDS, TimeUnit.SECONDS)
  } finally {
    state.closed.countDown()
  }
}

class LanguageServerInstallProgressTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  describe("a session") {
    it("never schedules nor cancels the display when no phase was opened") {
      val display = FakeDisplay()

      display.reporter().open().close()

      display.scheduled.size shouldBe 0
      display.cancelled.size shouldBe 0
    }

    it("schedules the display once, with the configured delay, on the first phase only") {
      val display = FakeDisplay()
      val session = display.reporter(delayMs = 1_234L).open()

      session.begin(InstallPhase.EXTRACTING)
      session.begin(InstallPhase.CLEANING_UP)

      display.scheduled.size shouldBe 1
      display.scheduled.single().second shouldBe 1_234L
      display.scheduled.single().first shouldBeSameInstanceAs display.job
      display.states.single().currentPhase.get() shouldBe InstallPhase.CLEANING_UP
    }

    it("cancels the display once on close, even when closed twice") {
      val display = FakeDisplay()
      val session = display.reporter().open()
      session.begin(InstallPhase.EXTRACTING)

      session.close()
      session.close()

      display.cancelled.size shouldBe 1
      display.cancelled.single() shouldBeSameInstanceAs display.job
      val state = display.states.single()
      state.currentPhase.get().shouldBeNull()
      state.closed.count shouldBe 0L
    }

    it("ignores a phase begun after close") {
      val display = FakeDisplay()
      val session = display.reporter().open()

      session.close()
      session.begin(InstallPhase.EXTRACTING)

      display.scheduled.size shouldBe 0
      display.states.forEach { it.currentPhase.get().shouldBeNull() }
    }

    it("ignores a phase begun after close once the display was scheduled") {
      val display = FakeDisplay()
      val session = display.reporter().open()
      session.begin(InstallPhase.EXTRACTING)

      session.close()
      session.begin(InstallPhase.WAITING_FOR_LOCK)

      display.scheduled.size shouldBe 1
      display.states.single().currentPhase.get().shouldBeNull()
    }

    it("does not clear a later phase when an earlier one ends") {
      val display = FakeDisplay()
      val session = display.reporter().open()

      session.begin(InstallPhase.WAITING_FOR_LOCK)
      session.begin(InstallPhase.EXTRACTING)
      session.end(InstallPhase.WAITING_FOR_LOCK)

      display.states.single().currentPhase.get() shouldBe InstallPhase.EXTRACTING

      session.end(InstallPhase.EXTRACTING)

      display.states.single().currentPhase.get().shouldBeNull()
    }

    it("ends the phase of a block that returns or throws") {
      val display = FakeDisplay()
      val session = display.reporter().open()

      session.during(InstallPhase.EXTRACTING) {
        display.states.single().currentPhase.get() shouldBe InstallPhase.EXTRACTING
        42
      } shouldBe 42
      display.states.single().currentPhase.get().shouldBeNull()

      shouldThrow<IllegalArgumentException> {
        session.during(InstallPhase.CLEANING_UP) { throw IllegalArgumentException("boom") }
      }
      display.states.single().currentPhase.get().shouldBeNull()
    }

    it("never throws when the Job manager refuses to schedule or cancel") {
      val shutDown = IllegalStateException("Job manager has been shut down.")
      val display = FakeDisplay(scheduleFailure = shutDown, cancelFailure = shutDown)
      val session = display.reporter().open()

      session.begin(InstallPhase.EXTRACTING)
      session.close()

      display.scheduled.size shouldBe 1
      display.cancelled.size shouldBe 1
    }

    it("returns at once from begin and close on a thread that holds a monitor") {
      val display = FakeDisplay()
      val session = display.reporter().open()
      val someLock = Any()

      val done = onThread {
        synchronized(someLock) {
          session.begin(InstallPhase.EXTRACTING)
          session.end(InstallPhase.EXTRACTING)
          session.close()
        }
        true
      }

      done.get(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
    }
  }

  describe("the reporter's defaults and failures") {
    it("delays the display by one second by default (D-C2)") {
      val scheduled = mutableListOf<Long>()
      val reporter = JobInstallProgressReporter(
        scheduleDisplay = { _, ms -> scheduled += ms },
        cancelDisplay = {},
        newDisplayJob = { mockk(relaxed = true) },
      )

      reporter.open().apply {
        begin(InstallPhase.EXTRACTING)
        close()
      }

      DISPLAY_DELAY shouldBe 1_000.milliseconds
      scheduled shouldBe listOf(1_000L)
    }

    it("opens a no-op session when the display Job cannot be made") {
      val reporter = JobInstallProgressReporter(newDisplayJob = { throw IllegalStateException("boom") })

      val session = reporter.open()

      session shouldBeSameInstanceAs InstallProgressSession.NONE
    }

    it("never throws from begin or close when even the log fails") {
      val previous = DebugLogging.isEnabled
      DebugLogging.isEnabled = { true }
      every { Platform.getLog(any<Bundle>()) } throws IllegalStateException("no log")
      every { Platform.getLog(any<Class<*>>()) } throws IllegalStateException("no log")
      try {
        val shutDown = IllegalStateException("Job manager has been shut down.")
        val display = FakeDisplay(scheduleFailure = shutDown, cancelFailure = shutDown)
        val session = display.reporter().open()

        session.begin(InstallPhase.EXTRACTING)
        session.close()

        display.scheduled.size shouldBe 1
        display.cancelled.size shouldBe 1
      } finally {
        DebugLogging.isEnabled = previous
      }
    }
  }

  describe("with the real Job manager") {
    it("schedules the display with the delay and cancels it on close, so it never runs") {
      val jobs = mutableListOf<Job>()
      val ran = AtomicInteger()
      val reporter = JobInstallProgressReporter(
        newDisplayJob = { state ->
          InstallProgressJob(state).also { job ->
            job.addJobChangeListener(
              object : JobChangeAdapter() {
                override fun running(event: IJobChangeEvent) {
                  ran.incrementAndGet()
                }
              },
            )
            jobs += job
          }
        },
      )
      val session = reporter.open()
      val job = jobs.single()
      try {
        session.begin(InstallPhase.EXTRACTING)
        job.state shouldBe Job.SLEEPING

        session.close()

        job.state shouldBe Job.NONE
        ran.get() shouldBe 0
      } finally {
        job.cancel()
      }
    }

    it("cancels a waiting display Job of the family and leaves other Jobs alone") {
      val mine = InstallProgressJob(InstallProgressState())
      val other = object : Job("other") {
        override fun run(monitor: IProgressMonitor?): IStatus = Status.OK_STATUS
      }
      mine.schedule(NEVER_MS)
      other.schedule(NEVER_MS)
      try {
        InstallProgressJobs.cancelAll()

        mine.state shouldBe Job.NONE
        other.state shouldBe Job.SLEEPING
      } finally {
        mine.cancel()
        other.cancel()
      }
    }
  }

  describe("the no-op reporter and session") {
    it("does nothing") {
      InstallProgressReporter.NONE.open() shouldBeSameInstanceAs InstallProgressSession.NONE
      val session = InstallProgressSession.NONE

      session.begin(InstallPhase.EXTRACTING)
      session.end(InstallPhase.EXTRACTING)
      session.during(InstallPhase.CLEANING_UP) { 1 } shouldBe 1
      session.close()
      session.close()
    }
  }

  describe("the display body") {
    it("returns OK once the session closes on another thread, after one beginTask and one done") {
      val state = InstallProgressState()
      val monitor = relaxedMonitor()
      val began = monitor.latchOn { it.beginTask(INSTALL_PROGRESS_JOB_NAME, IProgressMonitor.UNKNOWN) }

      val result = displayOnThread(state, monitor) {
        began.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
        state.closed.countDown()
      }

      result shouldBe Status.OK_STATUS
      verify(exactly = 1) { monitor.beginTask(any(), any()) }
      verify(exactly = 1) { monitor.done() }
    }

    it("reports each phase change as a sub task, once per change") {
      val state = InstallProgressState()
      val monitor = relaxedMonitor()
      val waiting = monitor.latchOn { it.subTask(installPhaseLabel(InstallPhase.WAITING_FOR_LOCK)) }
      val extracting = monitor.latchOn { it.subTask(installPhaseLabel(InstallPhase.EXTRACTING)) }
      state.currentPhase.set(InstallPhase.WAITING_FOR_LOCK)

      val result = displayOnThread(state, monitor) {
        waiting.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
        state.currentPhase.set(InstallPhase.EXTRACTING)
        extracting.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
        delay(50L)
        state.closed.countDown()
      }

      result shouldBe Status.OK_STATUS
      verify(exactly = 1) { monitor.subTask(installPhaseLabel(InstallPhase.WAITING_FOR_LOCK)) }
      verify(exactly = 1) { monitor.subTask(installPhaseLabel(InstallPhase.EXTRACTING)) }
    }

    it("reports CANCEL when the user cancels the display, without closing the session") {
      val state = InstallProgressState()
      val monitor = relaxedMonitor(cancelled = true)

      val future = onThread { runInstallProgressDisplay(state, monitor, pollMillis = 10L) }

      try {
        future.get(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe Status.CANCEL_STATUS
        state.closed.count shouldBe 1L
        verify(exactly = 1) { monitor.done() }
      } finally {
        state.closed.countDown()
      }
    }

    it("returns OK at once when the session is already closed") {
      val state = InstallProgressState()
      state.closed.countDown()
      val monitor = relaxedMonitor(cancelled = true)

      displayOnThread(state, monitor) {} shouldBe Status.OK_STATUS
      verify(exactly = 0) { monitor.subTask(any()) }
    }

    it("returns OK when its thread is interrupted") {
      val state = InstallProgressState()
      val monitor = relaxedMonitor()
      val began = monitor.latchOn { it.beginTask(any(), any()) }
      val thread = AtomicReference<Thread>()
      val future = onThread {
        thread.set(Thread.currentThread())
        runInstallProgressDisplay(state, monitor, pollMillis = 60_000L)
      }

      try {
        began.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
        thread.get().interrupt()

        future.get(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe Status.OK_STATUS
        verify(exactly = 1) { monitor.done() }
      } finally {
        state.closed.countDown()
      }
    }

    it("folds a failing monitor into OK") {
      val state = InstallProgressState()
      val monitor = relaxedMonitor()
      every { monitor.beginTask(any(), any()) } throws IllegalStateException("boom")

      displayOnThread(state, monitor) {}.severity shouldBe IStatus.OK
    }

    it("does not let a failing done escape") {
      val state = InstallProgressState()
      state.closed.countDown()
      val monitor = relaxedMonitor()
      every { monitor.done() } throws IllegalStateException("boom")

      displayOnThread(state, monitor) {} shouldBe Status.OK_STATUS
    }
  }

  describe("the display job") {
    it("belongs to the install progress family, is neither a user nor a system job, and uses the job name") {
      val job = InstallProgressJob(InstallProgressState())

      job.belongsTo(InstallProgressJobs.FAMILY) shouldBe true
      job.belongsTo(Any()) shouldBe false
      job.isUser shouldBe false
      job.isSystem shouldBe false
      job.name shouldBe INSTALL_PROGRESS_JOB_NAME
    }
  }

  describe("the wording") {
    it("uses the agreed job name and phase labels") {
      INSTALL_PROGRESS_JOB_NAME shouldBe "Starting GitLab Language Server"
      installPhaseLabel(InstallPhase.EXTRACTING) shouldBe "Installing the bundled language server..."
      installPhaseLabel(InstallPhase.WAITING_FOR_LOCK) shouldBe
        "Waiting for another Eclipse instance to finish installing the language server..."
      installPhaseLabel(InstallPhase.CLEANING_UP) shouldBe "Removing old language server versions..."
    }
  }
})
