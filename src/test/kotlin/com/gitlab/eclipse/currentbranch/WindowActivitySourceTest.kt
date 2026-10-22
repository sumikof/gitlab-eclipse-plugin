package com.gitlab.eclipse.currentbranch

import com.gitlab.eclipse.views.sidebar.autorefresh.Registration
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/** A shell tree stand-in: the walk is generic, so no SWT shell is needed. */
private class ShellNode(val parent: ShellNode? = null)

private class ActivityRig(var active: Boolean = false) {
  var installs = 0
  var uninstalls = 0
  var fanOut: (() -> Unit)? = null
  val deferred = mutableListOf<Runnable>()
  val filter = SharedActivationFilter(
    install = { callback ->
      installs++
      fanOut = callback
      Registration { uninstalls++ }
    },
    log = {},
  )
  val source = WindowActivitySource(
    isOwnShellActive = { active },
    defer = { deferred += it },
    filter = filter,
    log = {},
  )

  /** The platform settles the state, fires an Activate/Deactivate, and the deferred evaluation runs. */
  fun switchTo(value: Boolean) {
    active = value
    checkNotNull(fanOut)()
    runDeferred()
  }

  fun runDeferred() {
    val batch = deferred.toList()
    deferred.clear()
    batch.forEach { it.run() }
  }
}

class WindowActivitySourceTest : StringSpec({
  "D1: the window reads active when the active shell is its own shell or a dialog whose parent chain reaches it" {
    val window = ShellNode()
    val dialog = ShellNode(parent = window)
    val nested = ShellNode(parent = dialog)
    val other = ShellNode()
    val otherDialog = ShellNode(parent = other)

    shellChainReaches(window, window) { it.parent } shouldBe true
    shellChainReaches(dialog, window) { it.parent } shouldBe true
    shellChainReaches(nested, window) { it.parent } shouldBe true
    shellChainReaches(other, window) { it.parent } shouldBe false
    shellChainReaches(otherDialog, window) { it.parent } shouldBe false
    shellChainReaches(null, window) { it.parent } shouldBe false
  }

  "a parent chain that loops ends the walk as not active" {
    val window = ShellNode()
    val loop = ShellNode()
    shellChainReaches(loop, window) { loop } shouldBe false
  }

  "R7: registering installs the shared filter; isActive reads the window state" {
    val rig = ActivityRig(active = true)
    rig.installs shouldBe 0

    rig.source.isActive() shouldBe true
    rig.source.register {}

    rig.installs shouldBe 1
  }

  "a burst of events is evaluated once, after the platform settled, and only changes are reported" {
    val rig = ActivityRig(active = false)
    val changes = mutableListOf<Boolean>()
    rig.source.register { changes += it }

    rig.active = true
    repeat(3) { checkNotNull(rig.fanOut)() }
    rig.runDeferred()
    rig.switchTo(true)
    rig.switchTo(false)

    changes shouldContainExactly listOf(true, false)
  }

  "after unregister nothing is reported, even from an evaluation already deferred, and the filter is removed" {
    val rig = ActivityRig(active = false)
    val changes = mutableListOf<Boolean>()
    val registration = rig.source.register { changes += it }
    rig.active = true
    checkNotNull(rig.fanOut)()

    registration.unregister()
    rig.runDeferred()

    changes.shouldBeEmpty()
    rig.uninstalls shouldBe 1
  }

  "two windows on one filter each report their own state" {
    val aActive = false
    var bActive = false
    var fanOut: (() -> Unit)? = null
    val deferred = mutableListOf<Runnable>()
    val filter = SharedActivationFilter(
      install = { callback ->
        fanOut = callback
        Registration {}
      },
      log = {},
    )
    val a = WindowActivitySource({ aActive }, { deferred += it }, filter, log = {})
    val b = WindowActivitySource({ bActive }, { deferred += it }, filter, log = {})
    val seen = mutableListOf<String>()
    a.register { seen += "a=$it" }
    b.register { seen += "b=$it" }

    bActive = true
    checkNotNull(fanOut)()
    deferred.toList().also { deferred.clear() }.forEach { it.run() }

    seen shouldContainExactly listOf("b=true")
  }
})
