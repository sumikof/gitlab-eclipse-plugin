package com.gitlab.eclipse.ci.joblog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

private fun start(name: String, collapsed: Boolean = false) = SectionEvent.Start(name, collapsed)

private fun end(name: String) = SectionEvent.End(name)

/** Feeds one event list per line (index = line number) and returns the final state plus all regions in order. */
private fun run(vararg lines: List<SectionEvent>): Pair<SectionState, List<FoldRegion>> {
  var state = SectionState()
  val regions = ArrayList<FoldRegion>()
  lines.forEachIndexed { i, events ->
    val (next, done) = trackSection(state, i, events)
    state = next
    regions.addAll(done)
  }
  return state to regions
}

class SectionTrackerTest : DescribeSpec({
  describe("trackSection") {
    it("emits a region from the line after start through the line before end") {
      val (state, regions) = run(listOf(start("a")), emptyList(), emptyList(), listOf(end("a")))
      regions shouldBe listOf(FoldRegion(0, 2, false))
      state shouldBe SectionState()
    }

    it("carries collapsed=true") {
      run(listOf(start("a", true)), emptyList(), emptyList(), listOf(end("a"))).second shouldBe
        listOf(FoldRegion(0, 2, true))
    }

    it("closes nested sections inner first") {
      run(
        listOf(start("outer")),
        listOf(start("inner")),
        emptyList(),
        listOf(end("inner")),
        listOf(end("outer")),
      ).second shouldBe listOf(FoldRegion(1, 2, false), FoldRegion(0, 3, false))
    }

    it("emits nothing for a section that never ends") {
      val (state, regions) = run(listOf(start("a")), emptyList())
      regions shouldBe emptyList()
      state.open.size shouldBe 1
    }

    it("ignores an end without a start") {
      val (state, regions) = run(emptyList(), listOf(end("a")))
      regions shouldBe emptyList()
      state shouldBe SectionState()
    }

    it("ignores an end with a different name") {
      val (state, regions) = run(listOf(start("a")), emptyList(), listOf(end("b")))
      regions shouldBe emptyList()
      state.open.size shouldBe 1
    }

    it("matches an end to the innermost section of the same name") {
      run(
        listOf(start("a")),
        listOf(start("a")),
        emptyList(),
        listOf(end("a")),
        listOf(end("a")),
      ).second shouldBe listOf(FoldRegion(1, 2, false), FoldRegion(0, 3, false))
    }

    it("emits nothing when start and end are on the same line") {
      run(listOf(start("a"), end("a"))).second shouldBe emptyList()
    }

    it("emits nothing when the section has no body line") {
      run(listOf(start("a")), listOf(end("a"))).second shouldBe emptyList()
    }

    it("drops unterminated sections opened inside the one being closed") {
      val (state, regions) = run(
        listOf(start("outer")),
        listOf(start("lost")),
        emptyList(),
        listOf(end("outer")),
      )
      regions shouldBe listOf(FoldRegion(0, 2, false))
      state shouldBe SectionState()
    }

    it("processes several events of one line in order") {
      run(
        listOf(start("a")),
        emptyList(),
        listOf(end("a"), start("b")),
        emptyList(),
        listOf(end("b")),
      ).second shouldBe listOf(FoldRegion(0, 1, false), FoldRegion(2, 3, false))
    }
  }
})
