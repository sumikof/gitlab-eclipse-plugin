package com.gitlab.eclipse.mergerequests.discussions.actions

import com.gitlab.eclipse.mergerequests.discussions.LoadOutcome
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
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
 * `GitLabSidebarView` cannot be instantiated in this headless container, so every part of the fix is
 * asserted on the pure functions the view path delegates to.
 *
 * `reloadResolvedSections` adds the other half of the contract: on a live session the reload must
 * report **exactly once**, never zero times. Reporting zero times is `DiscussionsLoader`'s way of
 * saying "we are shutting down", and the launcher answers it by showing nothing at all — which,
 * applied to a live session whose sidebar was refreshed mid-write, would leave an Ambiguous write
 * unreported and silently discard the comment the user typed.
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

    it("ignores a duplicate report that arrives before the sibling has reported") {
      // The dangerous ordering. Without the per-slot guard the duplicate decrements the pending
      // counter a second time, driving it to zero while section "b" is still stale, and the
      // aggregate reports Applied — which is exactly what unlocks [Send again] and would invite a
      // duplicate comment. The trailing-duplicate case above passes even without the guard, so it
      // does not pin this.
      val reported = mutableListOf<LoadOutcome>()
      val callbacks = mutableListOf<(LoadOutcome) -> Unit>()

      reloadAllSections(listOf("a", "b"), { _, report -> callbacks += report }, { reported += it })
      callbacks[0](LoadOutcome.Applied)
      callbacks[0](LoadOutcome.Applied)

      reported.shouldBeEmpty()

      callbacks[1](LoadOutcome.Superseded)

      reported shouldContainExactly listOf(LoadOutcome.Superseded)
    }

    it("reports as soon as the only section reports, synchronously if that is how it reports") {
      val reported = mutableListOf<LoadOutcome>()

      reloadAllSections(listOf("only"), { _, report -> report(LoadOutcome.GateRejected) }, { reported += it })

      reported shouldContainExactly listOf(LoadOutcome.GateRejected)
    }
  }

  describe("reloadResolvedSections") {
    it("reports Skipped exactly once when the view cannot be resolved") {
      // No window, or the sidebar view is closed. The session is still alive — the launcher's
      // lifecycle guard passed in this same UI turn — so staying silent would leave an Ambiguous
      // write unreported and throw away the text the user typed.
      val reported = mutableListOf<LoadOutcome>()
      var started = 0

      reloadResolvedSections<String, String>(
        view = null,
        resolveSections = { error("must not resolve sections without a view") },
        reload = { _, _, _ -> started++ },
        onOutcome = { reported += it },
      )

      started shouldBe 0
      reported shouldContainExactly listOf(LoadOutcome.Skipped)
    }

    it("reports Skipped exactly once when the view resolves no section") {
      // The realistic case: the user refreshed the sidebar while the write was in flight, so the
      // rebuilt MR nodes are unexpanded and own no DiscussionsSectionNode.
      val reported = mutableListOf<LoadOutcome>()
      var started = 0

      reloadResolvedSections<String, String>(
        view = "view",
        resolveSections = { emptyList() },
        reload = { _, _, _ -> started++ },
        onOutcome = { reported += it },
      )

      started shouldBe 0
      reported shouldContainExactly listOf(LoadOutcome.Skipped)
    }

    it("never reports Applied for an empty section list, so [Send again] stays locked") {
      val reported = mutableListOf<LoadOutcome>()

      reloadResolvedSections<String, String>("view", { emptyList() }, { _, _, _ -> }, { reported += it })

      reported.none { it == LoadOutcome.Applied } shouldBe true
    }

    it("reloads every resolved section against the same view it resolved them from") {
      val reloaded = mutableListOf<Pair<String, String>>()

      reloadResolvedSections(
        view = "view",
        resolveSections = { listOf("assigned", "current-branch") },
        reload = { view, section, _ -> reloaded += view to section },
        onOutcome = { },
      )

      reloaded shouldContainExactly listOf("view" to "assigned", "view" to "current-branch")
    }

    it("reports exactly once, after every resolved section has reported") {
      val reported = mutableListOf<LoadOutcome>()
      val callbacks = mutableListOf<(LoadOutcome) -> Unit>()

      reloadResolvedSections("view", { listOf("a", "b") }, { _, _, report -> callbacks += report }, { reported += it })
      callbacks[0](LoadOutcome.Applied)

      reported.shouldBeEmpty()

      callbacks[1](LoadOutcome.Applied)

      reported shouldContainExactly listOf(LoadOutcome.Applied)
    }

    it("never reports twice, even when a section reports twice") {
      val reported = mutableListOf<LoadOutcome>()
      val callbacks = mutableListOf<(LoadOutcome) -> Unit>()

      reloadResolvedSections("view", { listOf("a", "b") }, { _, _, report -> callbacks += report }, { reported += it })
      callbacks[0](LoadOutcome.Applied)
      callbacks[1](LoadOutcome.Applied)
      callbacks[1](LoadOutcome.Superseded)
      callbacks[0](LoadOutcome.Superseded)

      reported shouldContainExactly listOf(LoadOutcome.Applied)
    }

    it("withholds Applied when one of the resolved sections did not apply") {
      val reported = mutableListOf<LoadOutcome>()
      val callbacks = mutableListOf<(LoadOutcome) -> Unit>()

      reloadResolvedSections("view", { listOf("a", "b") }, { _, _, report -> callbacks += report }, { reported += it })
      callbacks[0](LoadOutcome.Applied)
      callbacks[1](LoadOutcome.Superseded)

      reported shouldContainExactly listOf(LoadOutcome.Superseded)
    }

    it("resolves the sections exactly once") {
      var resolved = 0

      reloadResolvedSections(
        view = "view",
        resolveSections = {
          resolved++
          listOf("a")
        },
        reload = { _, _, _ -> },
        onOutcome = { },
      )

      resolved shouldBe 1
    }
  }
})
