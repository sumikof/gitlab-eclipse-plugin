package com.gitlab.eclipse.views.sidebar.autorefresh

import com.gitlab.eclipse.chat.quickchat.ui.UiScheduler
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.types.shouldBeSameInstanceAs

/** A fake display for the timer: records `timerExec` calls; runs nothing until the test does. */
private class TimerDisplay : UiScheduler {
  override var isDisposed = false
  var failTimer: Throwable? = null
  val timers = mutableListOf<Pair<Int, Runnable>>()

  override fun asyncExec(runnable: Runnable) = error("not used by the timer")

  override fun timerExec(millis: Int, runnable: Runnable) {
    failTimer?.let { throw it }
    timers += millis to runnable
  }
}

/** The `Display.timerExec` adapter (design §2.1/§4): scheduling and cancelling never throw into the refresher. */
class DisplayAutoRefreshTimerTest : StringSpec({
  "schedules with timerExec and cancels with timerExec(-1) on the same runnable" {
    val display = TimerDisplay()
    val ran = mutableListOf<String>()
    val cancellable = DisplayAutoRefreshTimer(display) {}.schedule(30_000) { ran += "tick" }
    val (millis, runnable) = display.timers.single()
    millis shouldBe 30_000
    runnable.run()
    ran shouldContainExactly listOf("tick")

    cancellable.cancel()
    display.timers[1].first shouldBe -1
    display.timers[1].second shouldBeSameInstanceAs runnable
  }

  "a disposed display schedules nothing and returns a harmless cancel" {
    val display = TimerDisplay().apply { isDisposed = true }
    DisplayAutoRefreshTimer(display) {}.schedule(300) { error("never") }.cancel()
    display.timers shouldBe emptyList()
  }

  "a scheduling failure (SWTError on a torn-down display) is contained and logged by class name" {
    val display = TimerDisplay().apply { failTimer = LinkageError("gone") }
    val logs = mutableListOf<String>()
    DisplayAutoRefreshTimer(display) { logs += it }.schedule(300) {}.cancel()
    logs.first() shouldEndWith "java.lang.LinkageError"
  }

  "a cancel after the display was disposed does nothing" {
    val display = TimerDisplay()
    val cancellable = DisplayAutoRefreshTimer(display) {}.schedule(300) {}
    display.isDisposed = true
    cancellable.cancel()
    display.timers.size shouldBe 1
  }

  "an action that throws is contained and logged by class name" {
    val display = TimerDisplay()
    val logs = mutableListOf<String>()
    DisplayAutoRefreshTimer(display) { logs += it }.schedule(300) { error("boom") }
    display.timers.single().second.run()
    logs.single() shouldEndWith "java.lang.IllegalStateException"
  }
})
