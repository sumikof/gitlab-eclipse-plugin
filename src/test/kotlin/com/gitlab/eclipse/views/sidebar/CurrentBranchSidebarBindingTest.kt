package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.currentbranch.StateRig
import com.gitlab.eclipse.mergerequests.CurrentBranchInfo
import com.gitlab.eclipse.views.sidebar.autorefresh.RefreshReason
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

private const val PERIOD = 30_000L
private const val GIT_DIR = "/home/alice/secret-repo/.git"

/**
 * The sidebar side of the binding: one view generation's slots, the apply port as the view's applyCompose (gate checked
 * when the update runs; here at once), and the sweep / cancel-sweep ports recorded.
 */
private class SidebarRig(val state: StateRig = StateRig()) {
  var generation = 1L
  var slots = RefreshSlots(generation)
  val events = mutableListOf<String>()
  val sweeps = mutableListOf<Boolean>()

  /** When set, composes queue up (the view's applyCompose is an asyncExec) until [flushComposes]. */
  var deferComposes = false
  private val composes = ArrayDeque<() -> Unit>()

  val binding = CurrentBranchSidebarBinding(
    currentSlots = { slots },
    cancelSweep = { events += "cancelSweep" },
    apply = { target, scope, soft, update ->
      events += "apply soft=$soft"
      val compose = { if (target.acceptsApply({ it == generation }, scope)) update() }
      if (deferComposes) composes += compose else compose()
    },
    requestSweep = { target, scope -> sweeps += target.acceptsApply({ it == generation }, scope) },
  )

  init {
    state.state.addListener(binding)
  }

  fun flushComposes() {
    while (composes.isNotEmpty()) composes.removeFirst()()
  }

  /** A new view generation, as refresh() starts one. */
  fun newGeneration(): RefreshSlots {
    generation++
    slots = RefreshSlots(generation)
    return slots
  }

  val shown: CurrentBranchSectionInput?
    get() = (slots.currentBranch as? Slot.Settled)?.value
}

class CurrentBranchSidebarBindingTest : StringSpec({
  "N1: initial refresh with nothing settled and nothing running starts one full fetch and binds it to the slots" {
    val rig = SidebarRig(StateRig(active = false))
    rig.state.state.start() // inactive: no fetch yet

    rig.binding.initial(rig.state.state, rig.slots)

    rig.state.fetch.calls shouldHaveSize 1
    rig.state.fetch.calls.single().soft shouldBe false
    val settled = rig.state.settleBranch()
    rig.shown shouldBeSameInstanceAs settled
    rig.slots.currentBranchGitDir shouldBe GIT_DIR
    rig.sweeps shouldContainExactly listOf(true)
  }

  "R9: initial refresh during the window's first refresh adopts it: no extra fetch, its applies land in the slots" {
    val rig = SidebarRig()
    rig.state.state.start() // active: INITIAL fetch running

    rig.binding.initial(rig.state.state, rig.slots)
    val settled = rig.state.settleBranch()

    rig.state.fetch.calls shouldHaveSize 1
    rig.shown shouldBeSameInstanceAs settled
    rig.sweeps shouldContainExactly listOf(true)
  }

  "R5/R10: initial refresh over a settled window seeds the exact instance and fetches nothing" {
    val rig = SidebarRig()
    rig.state.state.start()
    val settled = rig.state.settleBranch()

    rig.binding.initial(rig.state.state, rig.newGeneration())

    rig.state.fetch.calls shouldHaveSize 1
    rig.shown shouldBeSameInstanceAs settled
    rig.slots.currentBranchGitDir shouldBe GIT_DIR
  }

  "initial refresh over a settled window while a soft refresh runs seeds and adopts it" {
    val rig = SidebarRig()
    rig.state.state.start()
    rig.state.settleBranch()
    rig.state.time.advanceBy(PERIOD) // TIMER soft refresh running

    rig.binding.initial(rig.state.state, rig.newGeneration())
    val next = Resolved(Result.success(CurrentBranchInfo(null, emptyList())), Result.success(NoPipeline))
    rig.state.settleBranch(next)

    rig.state.fetch.calls shouldHaveSize 2
    rig.shown shouldBeSameInstanceAs next
  }

  "manual refresh: a full fetch whose applies land in the new generation; the superseded soft refresh paints nothing" {
    val rig = SidebarRig()
    rig.state.state.start()
    rig.state.settleBranch()
    rig.state.time.advanceBy(PERIOD)
    val soft = rig.state.fetch.calls.last()

    rig.binding.manual(rig.state.state, rig.newGeneration())
    soft.apply(GIT_DIR) { NoRepository } // late apply of the superseded refresh
    rig.state.ui.drain()
    rig.shown shouldBe null

    val settled = rig.state.settleBranch()
    rig.state.fetch.calls.last().soft shouldBe false
    rig.shown shouldBeSameInstanceAs settled
  }

  "a timer refresh started while the view shows the generation binds to it and applies as soft" {
    val rig = SidebarRig()
    rig.state.state.start()
    rig.binding.initial(rig.state.state, rig.slots)
    rig.state.settleBranch()
    rig.events.clear()

    rig.state.time.advanceBy(PERIOD)
    rig.state.settleBranch(Resolved(Result.success(CurrentBranchInfo(null, emptyList())), Result.success(NoPipeline)))

    rig.events shouldContainExactly listOf("cancelSweep", "apply soft=true")
  }

  "R10: a soft refresh that re-applies only the pipeline side keeps the MR side's Result instance in the slot" {
    val rig = SidebarRig()
    rig.state.state.start()
    rig.binding.initial(rig.state.state, rig.slots)
    val first = rig.state.settleBranch()
    rig.state.time.advanceBy(PERIOD)

    rig.state.fetch.calls.last().apply(GIT_DIR) { current ->
      (current as Resolved).copy(pipeline = Result.success(AmbiguousTags))
    }
    rig.state.ui.drain()

    (rig.shown as Resolved).mr shouldBeSameInstanceAs first.mr
  }

  "an apply whose compose has not run yet survives a coalesced refresh starting right after the finish" {
    val rig = SidebarRig()
    rig.state.state.start()
    rig.binding.initial(rig.state.state, rig.slots)
    rig.state.settleBranch()
    rig.state.time.advanceBy(PERIOD) // soft refresh 2
    rig.state.state.requestRefresh(RefreshReason.BRANCH) // coalesces
    rig.deferComposes = true
    val changed = Resolved(Result.success(CurrentBranchInfo(null, emptyList())), Result.success(AmbiguousTags))

    rig.state.settleBranch(changed) // apply + finish hops run; the finish starts the COALESCED refresh 3
    rig.state.fetch.calls shouldHaveSize 3
    rig.flushComposes()

    rig.shown shouldBeSameInstanceAs changed
  }

  "a seed whose compose has not run yet survives a refresh starting before it" {
    val rig = SidebarRig()
    rig.state.state.start()
    val settled = rig.state.settleBranch()
    rig.deferComposes = true

    rig.binding.initial(rig.state.state, rig.newGeneration())
    rig.state.time.advanceBy(PERIOD) // a timer refresh binds (new epoch) before the seed's compose ran
    rig.flushComposes()

    rig.shown shouldBeSameInstanceAs settled
  }

  "the downstream sweep runs only for a refresh that finished normally (non-null outcome)" {
    val rig = SidebarRig()
    rig.state.state.start()
    rig.binding.initial(rig.state.state, rig.slots)

    rig.state.fetch.calls.last().fail(IllegalStateException("boom"))
    rig.state.ui.drain()

    rig.sweeps.shouldBeEmpty()
  }

  "a superseded refresh never finishes, and nothing waits for it: the next one binds and sweeps normally" {
    val rig = SidebarRig()
    rig.state.state.start()
    rig.binding.initial(rig.state.state, rig.slots)

    rig.binding.manual(rig.state.state, rig.newGeneration())
    rig.state.settleBranch()

    rig.sweeps shouldContainExactly listOf(true)
    rig.binding.boundRefreshes shouldBe 0
  }

  "a refresh started before the view has slots is not bound; applies without a binding are dropped" {
    val state = StateRig()
    val events = mutableListOf<String>()
    val binding = CurrentBranchSidebarBinding(
      currentSlots = { null },
      cancelSweep = { events += "cancelSweep" },
      apply = { _, _, _, _ -> events += "apply" },
      requestSweep = { _, _ -> events += "sweep" },
    )
    state.state.addListener(binding)
    state.state.start()
    state.settleBranch()

    events.shouldBeEmpty()
  }

  "no window state (service unavailable): the section settles as no repository instead of loading forever" {
    val rig = SidebarRig()

    rig.binding.initial(null, rig.slots)
    rig.shown shouldBe NoRepository

    rig.binding.manual(null, rig.newGeneration())
    rig.shown shouldBe NoRepository
  }

  "R9: a sweep requested by a finished refresh is rejected by its gate once a newer refresh has bound the slots" {
    val state = StateRig()
    val slots = RefreshSlots(1)
    val pending = mutableListOf<ApplyScope.CurrentBranch>()
    val binding = CurrentBranchSidebarBinding(
      currentSlots = { slots },
      cancelSweep = {},
      apply = { _, _, _, update -> update() },
      requestSweep = { _, scope -> pending += scope },
    )
    state.state.addListener(binding)
    state.state.start()
    binding.initial(state.state, slots)
    state.settleBranch() // refresh 1 finishes: sweep requested under its epoch
    state.time.advanceBy(PERIOD) // timer refresh 2 binds the same slots
    slots.acceptsApply({ it == 1L }, pending.single()) shouldBe false
  }

  "applies land in the slots the refresh was bound to, even if the view's current slots changed" {
    val rig = SidebarRig()
    rig.state.state.start()
    val bound = rig.slots
    rig.binding.initial(rig.state.state, bound)
    rig.slots = RefreshSlots(rig.generation) // new slots object, not bound
    val settled = rig.state.settleBranch()
    (bound.currentBranch as Slot.Settled).value shouldBeSameInstanceAs settled
    rig.slots.currentBranch shouldBe Slot.Pending
  }
})
