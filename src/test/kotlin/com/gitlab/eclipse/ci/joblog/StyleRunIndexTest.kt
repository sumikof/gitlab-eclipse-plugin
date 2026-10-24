package com.gitlab.eclipse.ci.joblog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

private val red = AnsiStyle(fg = AnsiColor.Named(1))
private val bold = AnsiStyle(bold = true)

// Runs at [2,5) red, [8,10) bold, [10,12) red (adjacent to the previous run, different style).
private val RUNS = listOf(StyleRun(2, 3, red), StyleRun(8, 2, bold), StyleRun(10, 2, red))

class StyleRunIndexTest : DescribeSpec({
  describe("runsIn") {
    val index = StyleRunIndex.of(RUNS)

    it("is empty for the empty index") {
      StyleRunIndex.EMPTY.runsIn(0, 100) shouldBe emptyList()
      StyleRunIndex.EMPTY.size shouldBe 0
    }

    it("reports its size") {
      index.size shouldBe 3
    }

    it("returns everything for a covering range") {
      index.runsIn(0, 100) shouldBe RUNS
    }

    it("returns nothing for an empty or negative length") {
      index.runsIn(3, 0) shouldBe emptyList()
      index.runsIn(3, -4) shouldBe emptyList()
    }

    it("returns nothing before the first run") {
      index.runsIn(0, 2) shouldBe emptyList()
    }

    it("returns nothing in a gap between runs") {
      index.runsIn(5, 3) shouldBe emptyList()
    }

    it("returns nothing after the last run") {
      index.runsIn(12, 5) shouldBe emptyList()
    }

    it("treats the end of a run as exclusive") {
      index.runsIn(5, 1) shouldBe emptyList()
      index.runsIn(4, 1) shouldBe listOf(StyleRun(4, 1, red))
    }

    it("clips a run that starts before the range") {
      index.runsIn(3, 10) shouldBe listOf(StyleRun(3, 2, red), StyleRun(8, 2, bold), StyleRun(10, 2, red))
    }

    it("clips a run that ends after the range") {
      index.runsIn(0, 4) shouldBe listOf(StyleRun(2, 2, red))
    }

    it("clips both ends of a single run") {
      index.runsIn(3, 1) shouldBe listOf(StyleRun(3, 1, red))
    }

    it("returns runs in order across several matches") {
      index.runsIn(9, 2) shouldBe listOf(StyleRun(9, 1, bold), StyleRun(10, 1, red))
    }
  }

  describe("appendFrom") {
    val index = StyleRunIndex.of(RUNS)

    it("appends after the last run") {
      val next = index.appendFrom(20, listOf(StyleRun(20, 2, bold)))
      next.toList() shouldBe RUNS + StyleRun(20, 2, bold)
    }

    it("drops runs from replaceFrom onward") {
      val next = index.appendFrom(8, listOf(StyleRun(9, 1, bold)))
      next.toList() shouldBe listOf(StyleRun(2, 3, red), StyleRun(9, 1, bold))
    }

    it("truncates a run that straddles replaceFrom") {
      val next = index.appendFrom(4, emptyList())
      next.toList() shouldBe listOf(StyleRun(2, 2, red))
    }

    it("replaces everything when replaceFrom is 0") {
      index.appendFrom(0, listOf(StyleRun(0, 1, bold))).toList() shouldBe listOf(StyleRun(0, 1, bold))
    }

    it("does not change the original index") {
      index.appendFrom(0, emptyList())
      index.toList() shouldBe RUNS
    }

    it("matches an index built in one go") {
      val built = StyleRunIndex.EMPTY.appendFrom(0, RUNS.take(1)).appendFrom(5, RUNS.drop(1))
      built shouldBe StyleRunIndex.of(RUNS)
    }
  }
})
