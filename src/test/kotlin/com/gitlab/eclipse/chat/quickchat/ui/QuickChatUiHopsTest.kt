package com.gitlab.eclipse.chat.quickchat.ui

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs

/** A fake display: records what is posted and scheduled; runs nothing until the test says so. */
private class FakeScheduler : UiScheduler {
  override var isDisposed = false
  var failAsync: Throwable? = null
  var failTimer: Throwable? = null
  val posted = mutableListOf<Runnable>()
  val timers = mutableListOf<Pair<Int, Runnable>>()

  override fun asyncExec(runnable: Runnable) {
    failAsync?.let { throw it }
    posted += runnable
  }

  override fun timerExec(millis: Int, runnable: Runnable) {
    failTimer?.let { throw it }
    timers += millis to runnable
  }
}

class QuickChatUiHopsTest : DescribeSpec({
  describe("uiHop") {
    it("posts the block and runs it on the UI turn") {
      val scheduler = FakeScheduler()
      val ran = mutableListOf<String>()
      uiHop(scheduler) {}.invoke { ran += "block" }
      ran shouldBe emptyList()
      scheduler.posted.single().run()
      ran shouldContainExactly listOf("block")
    }

    it("contains a scheduling failure with a class-name-only log") {
      val scheduler = FakeScheduler().apply { failAsync = IllegalStateException("secret question") }
      val logs = mutableListOf<String>()
      uiHop(scheduler) { logs += it }.invoke { error("never") }
      logs.single() shouldContain "IllegalStateException"
      logs.single() shouldNotContain "secret question"
    }

    it("contains an Error thrown while scheduling (SWTError on a torn-down display)") {
      val scheduler = FakeScheduler().apply { failAsync = LinkageError("gone") }
      val logs = mutableListOf<String>()
      uiHop(scheduler) { logs += it }.invoke { }
      logs shouldHaveSize 1
    }

    it("skips a disposed display without scheduling") {
      val scheduler = FakeScheduler().apply { isDisposed = true }
      val logs = mutableListOf<String>()
      uiHop(scheduler) { logs += it }.invoke { error("never") }
      scheduler.posted shouldBe emptyList()
    }

    it("contains a failure inside the block with a class-name-only log") {
      val scheduler = FakeScheduler()
      val logs = mutableListOf<String>()
      uiHop(scheduler) { logs += it }.invoke { throw IllegalArgumentException("secret answer") }
      scheduler.posted.single().run()
      logs.single() shouldContain "IllegalArgumentException"
      logs.single() shouldNotContain "secret answer"
    }

    it("still contains a block failure when logging itself throws") {
      val scheduler = FakeScheduler()
      uiHop(scheduler) { error("log broken") }.invoke { error("block broken") }
      scheduler.posted.single().run()
    }
  }

  describe("uiTimer") {
    it("schedules the action after the delay and runs it") {
      val scheduler = FakeScheduler()
      val ran = mutableListOf<String>()
      uiTimer(scheduler).invoke(250L) { ran += "deadline" }
      val (millis, runnable) = scheduler.timers.single()
      millis shouldBe 250
      runnable.run()
      ran shouldContainExactly listOf("deadline")
    }

    it("clamps the delay into timerExec's int range") {
      val scheduler = FakeScheduler()
      uiTimer(scheduler).invoke(-5L) {}
      uiTimer(scheduler).invoke(Long.MAX_VALUE) {}
      scheduler.timers.map { it.first } shouldContainExactly listOf(0, Int.MAX_VALUE)
    }

    it("cancels with timerExec(-1) on the very runnable it scheduled") {
      val scheduler = FakeScheduler()
      val cancellable = uiTimer(scheduler).invoke(1_000L) {}
      val scheduled = scheduler.timers.single().second
      cancellable.cancel()
      scheduler.timers shouldHaveSize 2
      scheduler.timers[1].first shouldBe -1
      scheduler.timers[1].second shouldBeSameInstanceAs scheduled
    }

    it("lets a scheduling failure reach the session, which ends the send") {
      val scheduler = FakeScheduler().apply { failTimer = IllegalStateException("disposed") }
      runCatching { uiTimer(scheduler).invoke(1L) {} }.isFailure shouldBe true
    }
  }
})
