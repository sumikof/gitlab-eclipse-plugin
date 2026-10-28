package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.Status
import org.eclipse.core.runtime.jobs.Job
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

private const val WAIT_SECONDS = 10L
private const val VERIFY_TIMEOUT_MS = 5_000L

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

      val result = onThread { runInstallProgressDisplay(state, monitor, pollMillis = 10L) }
      verify(timeout = VERIFY_TIMEOUT_MS) { monitor.beginTask(INSTALL_PROGRESS_JOB_NAME, IProgressMonitor.UNKNOWN) }
      state.closed.countDown()

      result.get(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe Status.OK_STATUS
      verify(exactly = 1) { monitor.beginTask(any(), any()) }
      verify(exactly = 1) { monitor.done() }
    }

    it("reports each phase change as a sub task, once per change") {
      val state = InstallProgressState()
      val monitor = relaxedMonitor()
      state.currentPhase.set(InstallPhase.WAITING_FOR_LOCK)

      val result = onThread { runInstallProgressDisplay(state, monitor, pollMillis = 10L) }
      verify(timeout = VERIFY_TIMEOUT_MS) { monitor.subTask(installPhaseLabel(InstallPhase.WAITING_FOR_LOCK)) }
      state.currentPhase.set(InstallPhase.EXTRACTING)
      verify(timeout = VERIFY_TIMEOUT_MS) { monitor.subTask(installPhaseLabel(InstallPhase.EXTRACTING)) }
      delay(50L)
      state.closed.countDown()

      result.get(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe Status.OK_STATUS
      verify(exactly = 1) { monitor.subTask(installPhaseLabel(InstallPhase.WAITING_FOR_LOCK)) }
      verify(exactly = 1) { monitor.subTask(installPhaseLabel(InstallPhase.EXTRACTING)) }
    }

    it("reports CANCEL when the user cancels the display, without closing the session") {
      val state = InstallProgressState()
      val monitor = relaxedMonitor(cancelled = true)

      val result = onThread { runInstallProgressDisplay(state, monitor, pollMillis = 10L) }

      result.get(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe Status.CANCEL_STATUS
      state.closed.count shouldBe 1L
      verify(exactly = 1) { monitor.done() }
    }

    it("returns OK at once when the session is already closed") {
      val state = InstallProgressState()
      state.closed.countDown()
      val monitor = relaxedMonitor(cancelled = true)

      runInstallProgressDisplay(state, monitor, pollMillis = 10L) shouldBe Status.OK_STATUS
      verify(exactly = 0) { monitor.subTask(any()) }
    }

    it("returns OK when its thread is interrupted") {
      val state = InstallProgressState()
      val monitor = relaxedMonitor()
      val thread = arrayOfNulls<Thread>(1)

      val result = onThread {
        thread[0] = Thread.currentThread()
        runInstallProgressDisplay(state, monitor, pollMillis = 60_000L)
      }
      verify(timeout = VERIFY_TIMEOUT_MS) { monitor.beginTask(any(), any()) }
      thread[0]!!.interrupt()

      result.get(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe Status.OK_STATUS
      verify(exactly = 1) { monitor.done() }
    }

    it("folds a failing monitor into OK") {
      val state = InstallProgressState()
      val monitor = relaxedMonitor()
      every { monitor.beginTask(any(), any()) } throws IllegalStateException("boom")

      runInstallProgressDisplay(state, monitor, pollMillis = 10L).severity shouldBe IStatus.OK
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
