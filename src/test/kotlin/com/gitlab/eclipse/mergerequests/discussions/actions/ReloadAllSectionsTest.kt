package com.gitlab.eclipse.mergerequests.discussions.actions

import com.gitlab.eclipse.mergerequests.discussions.LoadOutcome
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

/**
 * The post-write re-fetch fan-out. One merge request can own TWO `DiscussionsSectionNode`s (it
 * appears under both "Merge requests assigned to me" and "For current branch"), so the reload must
 * refresh all of them and may only report [LoadOutcome.Applied] — the outcome that unlocks the
 * launcher's `[Send again]` — when every one of them actually applied. Reporting `Applied` while a
 * sibling section is still stale is what would make a user post the same comment twice.
 *
 * `GitLabSidebarView` cannot be instantiated in this headless container, so both halves of the fix
 * are asserted on the pure functions the view path delegates to.
 */
class ReloadAllSectionsTest : DescribeSpec({
  describe("aggregateReloadOutcomes") {
    it("reports Applied for a single applied section") {
      aggregateReloadOutcomes(listOf(LoadOutcome.Applied)) shouldBe LoadOutcome.Applied
    }

    it("reports Applied only when every section applied") {
      aggregateReloadOutcomes(listOf(LoadOutcome.Applied, LoadOutcome.Applied)) shouldBe LoadOutcome.Applied
    }

    it("does not report Applied when the second section was superseded") {
      // The defect's core case: one display refreshed, the other left stale.
      aggregateReloadOutcomes(listOf(LoadOutcome.Applied, LoadOutcome.Superseded)) shouldBe LoadOutcome.Superseded
    }

    it("does not report Applied when the first section was superseded") {
      aggregateReloadOutcomes(listOf(LoadOutcome.Superseded, LoadOutcome.Applied)) shouldBe LoadOutcome.Superseded
    }

    it("propagates the Failed instance, cause and all") {
      val failure = LoadOutcome.Failed(IllegalStateException("boom"))

      aggregateReloadOutcomes(listOf(LoadOutcome.Applied, failure)) shouldBeSameInstanceAs failure
    }

    it("propagates GateRejected") {
      aggregateReloadOutcomes(listOf(LoadOutcome.Applied, LoadOutcome.GateRejected)) shouldBe LoadOutcome.GateRejected
    }

    it("propagates Skipped") {
      aggregateReloadOutcomes(listOf(LoadOutcome.Applied, LoadOutcome.Skipped)) shouldBe LoadOutcome.Skipped
    }

    it("reports the FIRST non-Applied outcome in order") {
      val first = LoadOutcome.Failed(IllegalStateException("first"))

      aggregateReloadOutcomes(listOf(first, LoadOutcome.Superseded)) shouldBeSameInstanceAs first
    }

    it("maps the empty list to Skipped, never Applied") {
      // Unreachable from reloadAllSections (it returns without reporting for no sections), but the
      // function is total, and "nothing was reloaded" must never unlock a re-send.
      aggregateReloadOutcomes(emptyList()) shouldBe LoadOutcome.Skipped
    }
  }

  describe("reloadAllSections") {
    it("does not report at all when there is no section to reload") {
      val reported = mutableListOf<LoadOutcome>()
      var started = 0

      reloadAllSections<String>(emptyList(), { _, _ -> started++ }, { reported += it })

      started shouldBe 0
      reported shouldContainExactly emptyList()
    }

    it("starts a reload for every section") {
      val started = mutableListOf<String>()

      reloadAllSections(listOf("assigned", "current-branch"), { section, _ -> started += section }, { })

      started shouldContainExactly listOf("assigned", "current-branch")
    }

    it("stays silent while only one of two sections has reported") {
      val reported = mutableListOf<LoadOutcome>()
      val callbacks = mutableListOf<(LoadOutcome) -> Unit>()

      reloadAllSections(listOf("a", "b"), { _, report -> callbacks += report }, { reported += it })
      callbacks[0](LoadOutcome.Applied)

      reported shouldContainExactly emptyList()
    }

    it("reports exactly once, after both sections have reported") {
      val reported = mutableListOf<LoadOutcome>()
      val callbacks = mutableListOf<(LoadOutcome) -> Unit>()

      reloadAllSections(listOf("a", "b"), { _, report -> callbacks += report }, { reported += it })
      callbacks[0](LoadOutcome.Applied)
      callbacks[1](LoadOutcome.Applied)

      reported shouldContainExactly listOf(LoadOutcome.Applied)
    }

    it("aggregates in section order regardless of the order the sections report in") {
      val reported = mutableListOf<LoadOutcome>()
      val callbacks = mutableListOf<(LoadOutcome) -> Unit>()

      reloadAllSections(listOf("a", "b"), { _, report -> callbacks += report }, { reported += it })
      callbacks[1](LoadOutcome.Applied)
      callbacks[0](LoadOutcome.Superseded)

      reported shouldContainExactly listOf(LoadOutcome.Superseded)
    }

    it("withholds Applied when one section did not apply") {
      val reported = mutableListOf<LoadOutcome>()
      val callbacks = mutableListOf<(LoadOutcome) -> Unit>()

      reloadAllSections(listOf("a", "b"), { _, report -> callbacks += report }, { reported += it })
      callbacks[0](LoadOutcome.Applied)
      callbacks[1](LoadOutcome.Superseded)

      reported shouldContainExactly listOf(LoadOutcome.Superseded)
    }

    it("reports once even when a section reports twice") {
      val reported = mutableListOf<LoadOutcome>()
      val callbacks = mutableListOf<(LoadOutcome) -> Unit>()

      reloadAllSections(listOf("a", "b"), { _, report -> callbacks += report }, { reported += it })
      callbacks[0](LoadOutcome.Applied)
      callbacks[1](LoadOutcome.Applied)
      callbacks[1](LoadOutcome.Superseded)

      reported shouldContainExactly listOf(LoadOutcome.Applied)
    }

    it("reports as soon as the only section reports, synchronously if that is how it reports") {
      val reported = mutableListOf<LoadOutcome>()

      reloadAllSections(listOf("only"), { _, report -> report(LoadOutcome.GateRejected) }, { reported += it })

      reported shouldContainExactly listOf(LoadOutcome.GateRejected)
    }
  }
})
