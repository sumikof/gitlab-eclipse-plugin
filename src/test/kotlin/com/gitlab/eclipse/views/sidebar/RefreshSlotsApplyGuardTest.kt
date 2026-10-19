package com.gitlab.eclipse.views.sidebar

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The view's apply guard (CI-04 design §2.4/§4, ISSUE-01 design §7): an apply touches its slots only while their
 * generation is current, and a scoped apply only while its own epoch is still the latest — a current-branch apply
 * against [RefreshSlots.currentBranchEpoch], a queries apply against [RefreshSlots.queriesEpoch], never the other's.
 */
class RefreshSlotsApplyGuardTest : StringSpec({
  val current: (Long) -> Boolean = { it == 5L }

  "a Generation apply (mode change) only needs a current generation" {
    RefreshSlots(5).acceptsApply(current, ApplyScope.Generation) shouldBe true
    RefreshSlots(4).acceptsApply(current, ApplyScope.Generation) shouldBe false
  }

  "a current-branch apply needs a current generation and the latest current-branch epoch" {
    val slots = RefreshSlots(5).apply { currentBranchEpoch = 2 }
    slots.acceptsApply(current, ApplyScope.CurrentBranch(2)) shouldBe true
    slots.acceptsApply(current, ApplyScope.CurrentBranch(1)) shouldBe false
    RefreshSlots(4).apply { currentBranchEpoch = 2 }.acceptsApply(current, ApplyScope.CurrentBranch(2)) shouldBe false
  }

  "a queries apply needs a current generation and the latest queries epoch" {
    val slots = RefreshSlots(5).apply { queriesEpoch = 3 }
    slots.acceptsApply(current, ApplyScope.Queries(3)) shouldBe true
    slots.acceptsApply(current, ApplyScope.Queries(2)) shouldBe false
    RefreshSlots(4).apply { queriesEpoch = 3 }.acceptsApply(current, ApplyScope.Queries(3)) shouldBe false
  }

  "a queries apply ignores the current-branch epoch and vice versa" {
    val slots = RefreshSlots(5).apply {
      queriesEpoch = 1
      currentBranchEpoch = 7
    }
    slots.acceptsApply(current, ApplyScope.Queries(1)) shouldBe true
    slots.acceptsApply(current, ApplyScope.Queries(7)) shouldBe false
    slots.acceptsApply(current, ApplyScope.CurrentBranch(7)) shouldBe true
    slots.acceptsApply(current, ApplyScope.CurrentBranch(1)) shouldBe false
  }

  "fresh slots start at epoch 0 with every default query pending" {
    val slots = RefreshSlots(5)
    slots.queriesEpoch shouldBe 0
    slots.queries.outcomes.values.toSet() shouldBe setOf(Slot.Pending)
    slots.queries.outcomes.keys.toList() shouldBe slots.queries.queries.map { it.id }
  }
})
