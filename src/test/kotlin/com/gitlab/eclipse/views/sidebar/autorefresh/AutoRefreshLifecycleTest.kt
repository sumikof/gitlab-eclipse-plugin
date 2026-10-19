package com.gitlab.eclipse.views.sidebar.autorefresh

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.Job

/**
 * The view's auto-refresh lifecycle (design §2.4/§5): what `createPartControl` starts and `dispose`
 * must take down — the refresher's listeners and timers and the running soft refresh — so a closed
 * view leaves nothing registered with JGit or the Display. Uses the real refresher on fakes.
 */
class AutoRefreshLifecycleTest : StringSpec({
  class Rig {
    val time = VirtualTime()
    val activity = FakeActivity(active = true)
    val refs = FakeRefs()
    val logs = mutableListOf<String>()
    val lifecycle = AutoRefreshLifecycle { logs += it }

    fun refresher() = CurrentBranchAutoRefresher(time, time, activity, refs, FakeProbe(), FakePort(), { logs += it })
  }

  "start() creates and starts the refresher" {
    val rig = Rig()
    rig.lifecycle.start { rig.refresher() }
    rig.lifecycle.refresher.shouldNotBeNull()
    rig.activity.registered shouldBe 1
    rig.refs.registered shouldBe 1
  }

  "dispose() stops the refresher (listeners removed, timers cancelled) and cancels the soft refresh" {
    val rig = Rig()
    rig.lifecycle.start { rig.refresher() }
    val soft = Job()
    rig.lifecycle.replaceSoftJob(soft)

    rig.lifecycle.dispose()

    rig.activity.unregistered shouldBe 1
    rig.refs.unregistered shouldBe 1
    rig.time.pendingCount shouldBe 0
    soft.isCancelled shouldBe true
  }

  "a new soft refresh cancels the previous one, and cancelSoftJob() cancels the running one" {
    val rig = Rig()
    val first = Job()
    val second = Job()
    rig.lifecycle.replaceSoftJob(first)
    rig.lifecycle.replaceSoftJob(second)
    first.isCancelled shouldBe true
    second.isCancelled shouldBe false
    rig.lifecycle.cancelSoftJob()
    second.isCancelled shouldBe true
  }

  "a refresher that cannot be created leaves auto refresh off, logged by class name only" {
    val rig = Rig()
    rig.lifecycle.start { error("no display for /secret/repo") }
    rig.lifecycle.refresher.shouldBeNull()
    rig.logs.single() shouldEndWith "java.lang.IllegalStateException"
    rig.logs.single() shouldNotContain "/secret/repo"
    rig.lifecycle.dispose()
  }

  "dispose() twice is safe" {
    val rig = Rig()
    rig.lifecycle.start { rig.refresher() }
    rig.lifecycle.dispose()
    rig.lifecycle.dispose()
    rig.activity.unregistered shouldBe 1
  }
})
