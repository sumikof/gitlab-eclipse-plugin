package com.gitlab.eclipse.chat.quickchat

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

class QuickChatDetachedJobsTest : DescribeSpec({
  val stuckCalls = mutableListOf<StuckCall>()
  val runtimes = mutableListOf<QuickChatRuntime>()

  fun stuckJob(runtime: QuickChatRuntime): Pair<Job, StuckCall> {
    val stuck = StuckCall().also { stuckCalls += it }
    val job = runtime.scope.launch { runInterruptible { stuck.block() } }
    stuck.awaitEntered() shouldBe true
    return job to stuck
  }

  fun runtime() = QuickChatRuntime().also { runtimes += it }

  afterEach {
    stuckCalls.forEach(StuckCall::release)
    stuckCalls.clear()
    runtimes.forEach(QuickChatRuntime::close)
    runtimes.clear()
    awaitNoDetachedJobs() shouldBe true
  }

  describe("track") {
    it("counts an incomplete job until it completes") {
      val (job, stuck) = stuckJob(runtime())
      val before = QuickChatDetachedJobs.count
      QuickChatDetachedJobs.track(job)
      QuickChatDetachedJobs.count shouldBe before + 1
      stuck.release()
      job.join()
      awaitNoDetachedJobs() shouldBe true
    }

    it("does not count a job that is already complete") {
      val job = Job().also { it.complete() }
      QuickChatDetachedJobs.track(job)
      QuickChatDetachedJobs.count shouldBe 0
    }

    it("keeps counting a cancelled job that ignores interrupts, until it returns") {
      val (job, stuck) = stuckJob(runtime())
      job.cancel()
      QuickChatDetachedJobs.track(job)
      QuickChatDetachedJobs.count shouldBe 1
      stuck.release()
      awaitNoDetachedJobs() shouldBe true
    }
  }

  describe("atLimit") {
    it("is reached at MAX_DETACHED and left again when one job returns") {
      val jobs = (1..QuickChatLimits.MAX_DETACHED).map { stuckJob(runtime()) }
      QuickChatDetachedJobs.atLimit() shouldBe false
      jobs.forEach { (job, _) -> QuickChatDetachedJobs.track(job) }
      QuickChatDetachedJobs.atLimit() shouldBe true
      jobs.first().second.release()
      jobs.first().first.join()
      awaitCount(QuickChatLimits.MAX_DETACHED - 1)
      QuickChatDetachedJobs.atLimit() shouldBe false
    }
  }

  describe("QuickChatRuntime (A34)") {
    it("close cancels the scope without joining; the count survives a new runtime") {
      val first = runtime()
      val (job, stuck) = stuckJob(first)
      QuickChatDetachedJobs.track(job)
      first.close()
      first.scope.isActive shouldBe false
      job.isCancelled shouldBe true
      val second = runtime()
      second.scope.isActive shouldBe true
      QuickChatDetachedJobs.count shouldBe 1
      stuck.release()
      awaitNoDetachedJobs() shouldBe true
    }

    it("a launch on a closed runtime completes as cancelled without running") {
      val runtime = runtime()
      runtime.close()
      val ran = CompletableDeferred<Unit>()
      val job = runtime.scope.launch { ran.complete(Unit) }
      job.join()
      job.isCancelled shouldBe true
      ran.isCompleted shouldBe false
    }
  }
})

private fun awaitCount(expected: Int) {
  val until = System.currentTimeMillis() + 5_000
  while (QuickChatDetachedJobs.count != expected && System.currentTimeMillis() < until) Thread.sleep(5)
  QuickChatDetachedJobs.count shouldBe expected
}
