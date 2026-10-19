package com.gitlab.eclipse.views.sidebar.autorefresh

import com.gitlab.eclipse.chat.quickchat.ui.UiScheduler
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain

private class HopDisplay : UiScheduler {
  override var isDisposed = false
  var failAsync: Throwable? = null
  val posted = mutableListOf<Runnable>()

  override fun asyncExec(runnable: Runnable) {
    failAsync?.let { throw it }
    posted += runnable
  }

  override fun timerExec(millis: Int, runnable: Runnable) = error("not used by the hop")
}

/** The background → UI hop of the auto refresh adapters (design §4): asyncExec only, never throws. */
class AutoRefreshHopTest : StringSpec({
  "posts the block and runs it on the UI turn" {
    val display = HopDisplay()
    val ran = mutableListOf<String>()
    autoRefreshHop(display) {}.invoke { ran += "block" }
    ran shouldBe emptyList()
    display.posted.single().run()
    ran shouldContainExactly listOf("block")
  }

  "drops the block on a disposed display" {
    val display = HopDisplay().apply { isDisposed = true }
    autoRefreshHop(display) {}.invoke { error("never") }
    display.posted shouldBe emptyList()
  }

  "contains a scheduling failure (SWTError on a torn-down display), class name only" {
    val display = HopDisplay().apply { failAsync = LinkageError("secret") }
    val logs = mutableListOf<String>()
    autoRefreshHop(display) { logs += it }.invoke { }
    logs.single() shouldEndWith "java.lang.LinkageError"
    logs.single() shouldNotContain "secret"
  }

  "contains a failure inside the block, class name only" {
    val display = HopDisplay()
    val logs = mutableListOf<String>()
    autoRefreshHop(display) { logs += it }.invoke { throw IllegalArgumentException("secret") }
    display.posted.single().run()
    logs.single() shouldEndWith "java.lang.IllegalArgumentException"
    logs.single() shouldNotContain "secret"
  }
})
