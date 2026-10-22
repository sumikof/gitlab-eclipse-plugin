package com.gitlab.eclipse.currentbranch

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.eclipse.ui.IWindowListener
import org.eclipse.ui.IWorkbench
import org.eclipse.ui.IWorkbenchWindow
import java.io.File

/** A service over a mocked workbench; every entry is a [StateRig] state (fakes only, no Display). */
private class ServiceRig(vararg open: IWorkbenchWindow) {
  val workbench = mockk<IWorkbench>(relaxed = true)
  val windowListener = slot<IWindowListener>()
  val rigs = mutableMapOf<IWorkbenchWindow, StateRig>()
  val created = mutableListOf<IWorkbenchWindow>()
  val logs = mutableListOf<String>()
  var failCreate: Exception? = null
  val closed = mutableSetOf<IWorkbenchWindow>()
  var filterDisposed = 0

  init {
    every { workbench.workbenchWindows } returns open.toList().toTypedArray()
    every { workbench.addWindowListener(capture(windowListener)) } returns Unit
  }

  val service = CurrentBranchStateService(
    createState = { window ->
      failCreate?.let { throw it }
      created += window
      StateRig(editor = File("/w${created.size}/A.kt")).also { rigs[window] = it }.state
    },
    workbench = { workbench },
    isWindowOpen = { it !in closed },
    onDispose = { filterDisposed++ },
    log = { logs += it },
  )
}

private fun window(): IWorkbenchWindow = mockk(relaxed = true)

class CurrentBranchStateServiceTest : StringSpec({
  "activate registers the window listener once and creates one started entry per open window" {
    val a = window()
    val b = window()
    val rig = ServiceRig(a, b)

    rig.service.activate()
    rig.service.activate()

    verify(exactly = 1) { rig.workbench.addWindowListener(any()) }
    rig.created shouldContainExactly listOf(a, b)
    rig.rigs.getValue(a).fetch.calls shouldHaveSize 1
    rig.rigs.getValue(b).fetch.calls shouldHaveSize 1
  }

  "ensureWindow is idempotent from both callers (trim first, then activation, then trim again)" {
    val a = window()
    val rig = ServiceRig(a)

    val fromTrim = rig.service.ensureWindow(a)
    rig.service.activate()
    val again = rig.service.ensureWindow(a)

    again shouldBeSameInstanceAs fromTrim
    rig.service.stateFor(a) shouldBeSameInstanceAs fromTrim
    rig.created shouldContainExactly listOf(a)
    rig.rigs.getValue(a).fetch.calls shouldHaveSize 1
  }

  "windowOpened creates the entry; windowClosed disposes it and forgets it" {
    val rig = ServiceRig()
    rig.service.activate()
    val a = window()

    rig.windowListener.captured.windowOpened(a)
    val state = rig.service.stateFor(a).shouldNotBeNull()
    rig.windowListener.captured.windowClosed(a)

    state.isDisposed shouldBe true
    rig.service.stateFor(a).shouldBeNull()
    rig.rigs.getValue(a).activity.unregistered shouldBe 1
  }

  "T3b: a window that is closed (or closing) gets no entry, e.g. a trim control created during the close" {
    val a = window()
    val rig = ServiceRig(a)
    rig.service.activate()
    rig.windowListener.captured.windowClosed(a)
    rig.closed += a

    rig.service.ensureWindow(a).shouldBeNull()
    rig.service.stateFor(a).shouldBeNull()
    rig.created shouldContainExactly listOf(a)
  }

  "T3b: a window-liveness check that throws counts as closed" {
    val a = window()
    val rig = ServiceRig()
    val service = CurrentBranchStateService(
      createState = { error("must not be created") },
      workbench = { rig.workbench },
      isWindowOpen = { throw IllegalStateException("workbench gone") },
      onDispose = {},
      log = { rig.logs += it },
    )

    service.ensureWindow(a).shouldBeNull()
  }

  "two windows get separate entries with their own editor file" {
    val a = window()
    val b = window()
    val rig = ServiceRig(a, b)
    rig.service.activate()

    rig.service.stateFor(a) shouldNotBeSameInstanceAs rig.service.stateFor(b)
    rig.rigs.getValue(a).fetch.calls.single().editorFile shouldBe File("/w1/A.kt")
    rig.rigs.getValue(b).fetch.calls.single().editorFile shouldBe File("/w2/A.kt")
  }

  "requestRefresh(window) reaches only that window's entry; unknown windows are ignored" {
    val a = window()
    val b = window()
    val rig = ServiceRig(a, b)
    rig.service.activate()
    rig.rigs.values.forEach { it.settleBranch() }

    rig.service.requestRefresh(a)
    rig.service.requestRefresh(window())

    rig.rigs.getValue(a).fetch.calls shouldHaveSize 2
    rig.rigs.getValue(b).fetch.calls shouldHaveSize 1
  }

  "a failing entry creation is total: null, logged by class only, and a later call can retry" {
    val a = window()
    val rig = ServiceRig()
    rig.failCreate = IllegalStateException("/home/alice/secret")

    rig.service.ensureWindow(a).shouldBeNull()
    rig.logs shouldContainExactly listOf(
      "Current-branch state for a window could not be created: java.lang.IllegalStateException",
    )

    rig.failCreate = null
    rig.service.ensureWindow(a).shouldNotBeNull()
  }

  "activate is total when the workbench cannot be reached" {
    val rig = ServiceRig()
    every { rig.workbench.addWindowListener(any()) } throws IllegalStateException("closing")
    every { rig.workbench.workbenchWindows } throws IllegalStateException("closing")

    rig.service.activate()

    rig.logs.forEach { it.startsWith("Current-branch state activation failed: ") shouldBe true }
  }

  "dispose disposes every entry, removes the window listener and the shared filter; afterwards nothing is created" {
    val a = window()
    val rig = ServiceRig(a)
    rig.service.activate()
    val state = rig.service.stateFor(a).shouldNotBeNull()

    rig.service.dispose()
    rig.service.dispose()

    state.isDisposed shouldBe true
    verify(exactly = 1) { rig.workbench.removeWindowListener(rig.windowListener.captured) }
    rig.filterDisposed shouldBe 1
    rig.service.stateFor(a).shouldBeNull()
    rig.service.ensureWindow(a).shouldBeNull()
    rig.service.activate()
    rig.windowListener.captured.windowOpened(window())
    rig.created shouldContainExactly listOf(a)
  }
})
