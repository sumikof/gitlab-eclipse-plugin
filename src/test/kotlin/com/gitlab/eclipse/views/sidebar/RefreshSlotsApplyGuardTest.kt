package com.gitlab.eclipse.views.sidebar

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The view's apply guard (CI-04 design §2.4/§4): an apply touches its slots only while their
 * generation is current, and a current-branch apply only while its epoch is still the latest — a
 * superseded soft refresh (or a full refresh's late apply after a soft one began) must paint nothing.
 */
class RefreshSlotsApplyGuardTest : StringSpec({
  val current: (Long) -> Boolean = { it == 5L }

  "an apply without an epoch (assigned roots, mode change) only needs a current generation" {
    RefreshSlots(5).acceptsApply(current, epoch = null) shouldBe true
    RefreshSlots(4).acceptsApply(current, epoch = null) shouldBe false
  }

  "a current-branch apply needs a current generation and the latest epoch" {
    val slots = RefreshSlots(5).apply { currentBranchEpoch = 2 }
    slots.acceptsApply(current, epoch = 2) shouldBe true
    slots.acceptsApply(current, epoch = 1) shouldBe false
    RefreshSlots(4).apply { currentBranchEpoch = 2 }.acceptsApply(current, epoch = 2) shouldBe false
  }
})
