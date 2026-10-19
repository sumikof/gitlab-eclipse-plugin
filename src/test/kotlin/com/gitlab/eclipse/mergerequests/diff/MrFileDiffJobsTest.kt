package com.gitlab.eclipse.mergerequests.diff

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.preferences.storage.CapturedLog
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.OperationCanceledException
import org.eclipse.core.runtime.jobs.IJobChangeEvent
import org.eclipse.core.runtime.jobs.Job
import org.eclipse.core.runtime.jobs.JobChangeAdapter
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A delay long enough that a scheduled job never starts during the test (it is cancelled first). */
private val NEVER_MS = TimeUnit.HOURS.toMillis(1)
private const val WAIT_SECONDS = 30L

class MrFileDiffJobsTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  fun monitor(cancelled: Boolean = false): IProgressMonitor = mockk { every { isCanceled } returns cancelled }

  describe("the job body") {
    it("runs the work and reports OK") {
      var ran = false

      MrFileDiffJobs.runWork({ ran = true }, monitor()).severity shouldBe IStatus.OK

      ran shouldBe true
    }

    it("hands the work the monitor's cancellation") {
      var seen: Boolean? = null

      MrFileDiffJobs.runWork({ isCancelled -> seen = isCancelled() }, monitor(cancelled = true))

      seen shouldBe true
    }

    it("reports CANCEL for a platform or a concurrent cancellation") {
      MrFileDiffJobs.runWork({ throw OperationCanceledException() }, monitor()) shouldBe
        org.eclipse.core.runtime.Status.CANCEL_STATUS
      MrFileDiffJobs.runWork({ throw CancellationException() }, monitor()) shouldBe
        org.eclipse.core.runtime.Status.CANCEL_STATUS
    }

    it("folds anything else into OK and logs only the outcome and the exception type") {
      val log = CapturedLog().also { it.install() }

      val status = MrFileDiffJobs.runWork({ throw IllegalStateException("secret/path.kt token") }, monitor())

      status.severity shouldBe IStatus.OK
      log.lines shouldHaveSize 1
      log.lines.single() shouldContain "outcome=GENERIC"
      log.lines.single() shouldContain "exceptionType=java.lang.IllegalStateException"
      log.lines.single() shouldNotContain "secret"
    }

    it("folds an Error too, so nothing escapes to the job manager") {
      MrFileDiffJobs.runWork({ throw AssertionError("boom") }, monitor()).severity shouldBe IStatus.OK
    }
  }

  describe("the job") {
    it("belongs to the MR diff family and to no other") {
      val job = MrFileDiffJobs.newJob("t", {}) {}

      job.belongsTo(MrFileDiffJobs.FAMILY) shouldBe true
      job.belongsTo(Any()) shouldBe false
      job.isUser shouldBe false
    }

    it("reports that it ended without running when cancelled before it started") {
      val endedWithoutRunning = CountDownLatch(1)
      val ran = AtomicInteger()
      val job = MrFileDiffJobs.newJob("t", { ran.incrementAndGet() }) { endedWithoutRunning.countDown() }

      job.schedule(NEVER_MS)
      job.cancel()

      endedWithoutRunning.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
      ran.get() shouldBe 0
    }

    it("does not report it when the work ran") {
      val notRun = AtomicInteger()
      val done = CountDownLatch(1)
      val job = MrFileDiffJobs.newJob("t", {}) { notRun.incrementAndGet() }
      job.addJobChangeListener(
        object : JobChangeAdapter() {
          override fun done(event: IJobChangeEvent) = done.countDown()
        },
      )

      job.schedule()

      done.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
      notRun.get() shouldBe 0
    }
  }

  describe("cancelAll") {
    it("cancels the family's waiting jobs and leaves other jobs alone") {
      val mine = MrFileDiffJobs.newJob("mine", {}) {}
      val other = object : Job("other") {
        override fun run(monitor: IProgressMonitor?): IStatus = org.eclipse.core.runtime.Status.OK_STATUS
      }
      mine.schedule(NEVER_MS)
      other.schedule(NEVER_MS)
      try {
        MrFileDiffJobs.cancelAll()

        mine.state shouldBe Job.NONE
        other.state shouldBe Job.SLEEPING
      } finally {
        mine.cancel()
        other.cancel()
      }
    }
  }
})
