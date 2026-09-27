package com.gitlab.eclipse.mergerequests.review

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

private fun diffOf(vararg lines: String) = lines.joinToString("\n")

class DiffLineMapTest : DescribeSpec({
  describe("DiffLineMap.parse / classify") {

    describe("a single modified hunk (@@ -3,4 +3,5 @@)") {
      val diff = diffOf(
        "@@ -3,4 +3,5 @@",
        " ctxA",
        "-removed1",
        "+added1",
        "+added2",
        " ctxB",
      )
      val map = DiffLineMap.parse(diff)

      data class Case(val newLine: Int, val expected: NewLineKind)
      listOf(
        Case(1, NewLineKind.Unchanged(1)),
        Case(2, NewLineKind.Unchanged(2)),
        Case(3, NewLineKind.Unchanged(3)),
        Case(4, NewLineKind.Added),
        Case(5, NewLineKind.Added),
        Case(6, NewLineKind.Unchanged(5)),
        Case(7, NewLineKind.Unchanged(6)),
      ).forEach { (newLine, expected) ->
        it("classifies new line $newLine as $expected") {
          map.classify(newLine) shouldBe expected
        }
      }
    }

    describe("multiple hunks") {
      val diff = diffOf(
        "@@ -3,4 +3,5 @@",
        " ctxA",
        "-removed1",
        "+added1",
        "+added2",
        " ctxB",
        "@@ -20,2 +21,2 @@",
        " ctxC",
        "+added3",
      )
      val map = DiffLineMap.parse(diff)

      it("classifies a line between the hunks using the first hunk's cumulative offset") {
        map.classify(10) shouldBe NewLineKind.Unchanged(9)
      }

      it("classifies a line after the last hunk using its cumulative offset") {
        map.classify(25) shouldBe NewLineKind.Unchanged(23)
      }
    }

    describe("a deletion-only hunk (+c,0)") {
      val diff = diffOf(
        "@@ -5,3 +5,0 @@",
        "-del1",
        "-del2",
        "-del3",
      )
      val map = DiffLineMap.parse(diff)

      it("classifies the line right after the deletion with a negative offset") {
        map.classify(5) shouldBe NewLineKind.Unchanged(8)
      }
    }

    describe("a new file (@@ -0,0 +1,3 @@)") {
      val diff = diffOf(
        "@@ -0,0 +1,3 @@",
        "+line1",
        "+line2",
        "+line3",
      )
      val map = DiffLineMap.parse(diff)

      data class Case(val newLine: Int)
      listOf(Case(1), Case(2), Case(3)).forEach { (newLine) ->
        it("classifies new line $newLine as Added") {
          map.classify(newLine) shouldBe NewLineKind.Added
        }
      }
    }

    describe("a diff ending with '\\ No newline at end of file'") {
      val diff = diffOf(
        "@@ -1,2 +1,2 @@",
        " first",
        "-second",
        "+second-modified",
        "\\ No newline at end of file",
      )
      val map = DiffLineMap.parse(diff)

      it("does not count the marker line, so the last real line classifies correctly") {
        map.classify(1) shouldBe NewLineKind.Unchanged(1)
        map.classify(2) shouldBe NewLineKind.Added
      }
    }

    describe("a hunk header omitting line counts (@@ -1 +1 @@)") {
      val diff = diffOf(
        "@@ -1 +1 @@",
        " only",
      )
      val map = DiffLineMap.parse(diff)

      it("parses the start lines correctly") {
        map.classify(1) shouldBe NewLineKind.Unchanged(1)
      }
    }

    describe("out-of-range line numbers") {
      val diff = diffOf(
        "@@ -3,4 +3,5 @@",
        " ctxA",
        "-removed1",
        "+added1",
        "+added2",
        " ctxB",
      )
      val map = DiffLineMap.parse(diff)

      it("throws IllegalArgumentException for line 0") {
        shouldThrow<IllegalArgumentException> { map.classify(0) }
      }

      it("throws IllegalArgumentException for a negative line") {
        shouldThrow<IllegalArgumentException> { map.classify(-1) }
      }
    }

    describe("DiffLineMap.Unavailable") {
      it("classifies any line as null") {
        DiffLineMap.Unavailable.classify(42) shouldBe null
      }

      it("still throws IllegalArgumentException for line 0") {
        shouldThrow<IllegalArgumentException> { DiffLineMap.Unavailable.classify(0) }
      }
    }

    describe("DiffLineMap.Identity") {
      it("classifies every line as Unchanged with the same line number") {
        DiffLineMap.Identity.classify(42) shouldBe NewLineKind.Unchanged(42)
      }

      it("still throws IllegalArgumentException for line 0") {
        shouldThrow<IllegalArgumentException> { DiffLineMap.Identity.classify(0) }
      }
    }
  }
})
