package com.gitlab.eclipse.ci.joblog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class ColoredRegionSetTest : DescribeSpec({
  describe("missingIn") {
    it("reports the whole range for an empty set") {
      ColoredRegionSet().missingIn(10, 5) shouldBe listOf(10..14)
    }

    it("reports nothing for an empty range") {
      ColoredRegionSet().missingIn(10, 0) shouldBe emptyList()
    }

    it("reports nothing when fully covered") {
      val set = ColoredRegionSet().apply { add(0, 100) }
      set.missingIn(10, 20) shouldBe emptyList()
    }

    it("reports the uncovered edges and gaps") {
      val set = ColoredRegionSet().apply {
        add(5, 3)
        add(12, 3)
      }
      set.missingIn(0, 20) shouldBe listOf(0..4, 8..11, 15..19)
    }

    it("handles a range starting inside a covered span") {
      val set = ColoredRegionSet().apply { add(0, 10) }
      set.missingIn(5, 10) shouldBe listOf(10..14)
    }
  }

  describe("add") {
    it("merges adjacent spans") {
      val set = ColoredRegionSet().apply {
        add(0, 5)
        add(5, 5)
      }
      set.missingIn(0, 10) shouldBe emptyList()
      set.missingIn(0, 11) shouldBe listOf(10..10)
    }

    it("merges overlapping spans") {
      val set = ColoredRegionSet().apply {
        add(0, 6)
        add(4, 6)
      }
      set.missingIn(0, 10) shouldBe emptyList()
    }

    it("bridges several spans") {
      val set = ColoredRegionSet().apply {
        add(0, 2)
        add(4, 2)
        add(8, 2)
        add(1, 8)
      }
      set.missingIn(0, 10) shouldBe emptyList()
    }

    it("ignores empty ranges") {
      val set = ColoredRegionSet().apply { add(3, 0) }
      set.missingIn(0, 10) shouldBe listOf(0..9)
    }
  }

  describe("subtract") {
    it("splits a span") {
      val set = ColoredRegionSet().apply {
        add(0, 10)
        subtract(3, 4)
      }
      set.missingIn(0, 10) shouldBe listOf(3..6)
    }

    it("trims the start and the end of spans") {
      val set = ColoredRegionSet().apply {
        add(0, 10)
        subtract(0, 2)
        subtract(8, 5)
      }
      set.missingIn(0, 10) shouldBe listOf(0..1, 8..9)
    }

    it("removes spans it fully covers") {
      val set = ColoredRegionSet().apply {
        add(2, 2)
        add(6, 2)
        subtract(0, 20)
      }
      set.missingIn(0, 10) shouldBe listOf(0..9)
    }

    it("does nothing outside any span or for an empty range") {
      val set = ColoredRegionSet().apply {
        add(5, 5)
        subtract(0, 5)
        subtract(10, 5)
        subtract(7, 0)
      }
      set.missingIn(0, 15) shouldBe listOf(0..4, 10..14)
    }
  }

  describe("add out of order") {
    it("keeps spans sorted when adding to the left of an existing span") {
      val set = ColoredRegionSet().apply {
        add(10, 5)
        add(0, 5)
      }
      set.missingIn(0, 20) shouldBe listOf(5..9, 15..19)
    }
  }

  describe("clear") {
    it("forgets everything") {
      val set = ColoredRegionSet().apply {
        add(0, 100)
        clear()
      }
      set.missingIn(0, 10) shouldBe listOf(0..9)
    }
  }
})
