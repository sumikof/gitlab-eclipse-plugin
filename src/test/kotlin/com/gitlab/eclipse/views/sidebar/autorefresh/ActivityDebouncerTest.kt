package com.gitlab.eclipse.views.sidebar.autorefresh

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.types.shouldBeSameInstanceAs

/**
 * The SWT-free core of the activity source (design §2.2, D3/D4): every Activate / Deactivate /
 * part-visibility signal re-arms ONE deferred evaluation (production: `timerExec(0, sameRunnable)`,
 * which SWT reschedules rather than duplicates), and the evaluation reports only real changes — so
 * a Deactivate→Activate pair between two workbench windows, or a modal dialog, reports nothing.
 */
class ActivityDebouncerTest : StringSpec({
  class Rig(var active: Boolean = true) {
    val deferred = mutableListOf<Runnable>()
    val changes = mutableListOf<Boolean>()
    val logs = mutableListOf<String>()
    var readFailure: RuntimeException? = null
    var deferFailure: RuntimeException? = null
    val debouncer = ActivityDebouncer(
      read = {
        readFailure?.let { throw it }
        active
      },
      defer = { runnable ->
        deferFailure?.let { throw it }
        deferred += runnable
      },
      log = { logs += it },
    )

    /** Runs the evaluation SWT would run once the burst is over. */
    fun settle() = deferred.last().run()
  }

  "a burst of signals re-arms one and the same deferred evaluation" {
    val rig = Rig()
    rig.debouncer.start { rig.changes += it }
    repeat(3) { rig.debouncer.signal() }
    rig.deferred.size shouldBe 3
    rig.deferred[1] shouldBeSameInstanceAs rig.deferred[0]
    rig.deferred[2] shouldBeSameInstanceAs rig.deferred[0]
  }

  "the evaluation reports a change once, and nothing when the state is back where it was" {
    val rig = Rig(active = true)
    rig.debouncer.start { rig.changes += it }
    rig.active = false
    rig.debouncer.signal()
    rig.settle()
    rig.settle()
    rig.active = true
    rig.debouncer.signal() // Deactivate
    rig.debouncer.signal() // Activate in the other window: still active when evaluated
    rig.settle()
    rig.changes shouldContainExactly listOf(false, true)
  }

  "a Deactivate/Activate pair that ends where it started reports nothing" {
    val rig = Rig(active = true)
    rig.debouncer.start { rig.changes += it }
    rig.debouncer.signal()
    rig.debouncer.signal()
    rig.settle()
    rig.changes shouldBe emptyList()
  }

  "a read that throws (disposed display) is logged by class name and reports nothing" {
    val rig = Rig()
    rig.debouncer.start { rig.changes += it }
    rig.readFailure = IllegalStateException("widget is disposed")
    rig.debouncer.signal()
    rig.settle()
    rig.changes shouldBe emptyList()
    rig.logs.single() shouldEndWith "java.lang.IllegalStateException"
  }

  "a defer that throws is logged by class name and does not escape the SWT listener" {
    val rig = Rig()
    rig.debouncer.start { rig.changes += it }
    rig.deferFailure = IllegalStateException("display disposed")
    rig.debouncer.signal()
    rig.logs.single() shouldEndWith "java.lang.IllegalStateException"
  }

  "a listener that throws is contained" {
    val rig = Rig()
    rig.debouncer.start { error("listener broke") }
    rig.active = false
    rig.debouncer.signal()
    rig.settle()
    rig.logs.single() shouldEndWith "java.lang.IllegalStateException"
  }

  "after stop() signals defer nothing and a late evaluation reports nothing" {
    val rig = Rig()
    rig.debouncer.start { rig.changes += it }
    rig.debouncer.signal()
    rig.debouncer.stop()
    rig.debouncer.signal()
    rig.active = false
    rig.settle()
    rig.deferred.size shouldBe 1
    rig.changes shouldBe emptyList()
  }

  "current() reads the state now, and a failed read counts as inactive" {
    val rig = Rig(active = true)
    rig.debouncer.current() shouldBe true
    rig.readFailure = IllegalStateException("gone")
    rig.debouncer.current() shouldBe false
  }
})
