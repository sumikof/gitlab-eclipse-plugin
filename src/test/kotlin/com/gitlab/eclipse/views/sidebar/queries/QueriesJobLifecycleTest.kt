package com.gitlab.eclipse.views.sidebar.queries

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

private class LifecycleHarness {
  val scheduler = TestCoroutineScheduler()
  val parent = Job()
  val scope = CoroutineScope(StandardTestDispatcher(scheduler) + parent)
  val logs = mutableListOf<String>()
  val lifecycle = QueriesJobLifecycle(scope) { logs += it }

  fun drain() = scheduler.advanceUntilIdle()
}

class QueriesJobLifecycleTest : StringSpec({
  "a second launch cancels the first; the first job's isActive turns false" {
    val h = LifecycleHarness()
    var firstActive: (() -> Boolean)? = null
    val first = h.lifecycle.launch { isActive ->
      firstActive = isActive
      awaitCancellation()
    }
    h.drain()
    firstActive!!() shouldBe true
    var secondRan = false
    h.lifecycle.launch { secondRan = true }
    h.drain()
    first!!.isCancelled shouldBe true
    firstActive!!() shouldBe false
    secondRan shouldBe true
  }

  "cancel stops the queries job only; a sibling job (fetchJob stand-in) keeps running and completes" {
    val h = LifecycleHarness()
    val gate = CompletableDeferred<Unit>()
    var siblingCompleted = false
    val sibling = h.scope.launch {
      gate.await()
      siblingCompleted = true
    }
    val queries = h.lifecycle.launch { awaitCancellation() }
    h.drain()
    h.lifecycle.cancel()
    h.drain()
    queries!!.isCancelled shouldBe true
    sibling.isActive shouldBe true
    gate.complete(Unit)
    h.drain()
    siblingCompleted shouldBe true
    sibling.isCancelled shouldBe false
    h.parent.isActive shouldBe true
  }

  "a new launch never cancels a sibling job" {
    val h = LifecycleHarness()
    val sibling = h.scope.launch { awaitCancellation() }
    h.lifecycle.launch { awaitCancellation() }
    h.lifecycle.launch { awaitCancellation() }
    h.drain()
    sibling.isActive shouldBe true
    sibling.cancel()
  }

  "dispose cancels the running job and later launches run nothing" {
    val h = LifecycleHarness()
    val running = h.lifecycle.launch { awaitCancellation() }
    h.drain()
    h.lifecycle.dispose()
    var ran = false
    val later = h.lifecycle.launch { ran = true }
    h.drain()
    running!!.isCancelled shouldBe true
    later shouldBe null
    ran shouldBe false
  }

  "a body failure never escapes into the shared scope and is logged by class name only" {
    val h = LifecycleHarness()
    val sibling = h.scope.launch { awaitCancellation() }
    h.lifecycle.launch { error("secret-message-text") }
    h.drain()
    h.parent.isActive shouldBe true
    sibling.isActive shouldBe true
    h.logs shouldContainExactly listOf("Sidebar queries refresh failed: exceptionType=java.lang.IllegalStateException")
    h.logs.single() shouldNotContain "secret-message-text"
    sibling.cancel()
  }
})
