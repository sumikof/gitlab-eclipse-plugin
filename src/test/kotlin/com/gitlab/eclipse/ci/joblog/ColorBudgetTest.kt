package com.gitlab.eclipse.ci.joblog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

class ColorBudgetTest : DescribeSpec({
  describe("nearestXterm256") {
    withData(
      nameFn = { (input, expected) -> "%06X -> %06X".format(input, expected) },
      0x010203 to 0x000000,
      0xFF0001 to 0xFF0000,
      0x5F87AF to 0x5F87AF, // a cube entry maps to itself
      0x7F7F7F to 0x808080, // gray ramp beats the cube
      0x080808 to 0x080808, // first gray step
      0xEEEEEE to 0xEEEEEE, // last gray step
      0xFEFEFE to 0xFFFFFF, // cube corner
      0x60FDFF to 0x5FFFFF,
    ) { (input, expected) ->
      nearestXterm256(input) shouldBe expected
    }
  }

  describe("ColorBudget") {
    it("keeps values as they are up to the limit, then maps new ones to the nearest palette entry") {
      val budget = ColorBudget(limit = 2)
      budget.map(0x123456) shouldBe 0x123456
      budget.map(0xABCDEF) shouldBe 0xABCDEF

      budget.map(0x010203) shouldBe 0x000000
      budget.map(0xFF0001) shouldBe 0xFF0000
    }

    it("a value admitted before the limit keeps its own color afterwards") {
      val budget = ColorBudget(limit = 1)
      budget.map(0x123456) shouldBe 0x123456
      budget.map(0x010203) shouldBe 0x000000
      budget.map(0x123456) shouldBe 0x123456
    }

    it("defaults to MAX_EDITOR_COLORS = 1024 distinct values") {
      MAX_EDITOR_COLORS shouldBe 1024
      val budget = ColorBudget()
      (1..MAX_EDITOR_COLORS).forEach { budget.map(it) shouldBe it }
      budget.map(0xFF0001) shouldBe 0xFF0000
    }
  }
})
