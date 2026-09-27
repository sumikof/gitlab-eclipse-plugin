package com.gitlab.eclipse.mergerequests.review

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

private fun diffOf(vararg lines: String) = lines.joinToString("\n")

class DiffLineMapTest : DescribeSpec({
  describe("DiffLineMap.parse / classify") {

    describe("a single modified hunk (@@ -3,3 +3,4 @@)") {
      val diff = diffOf(
        "@@ -3,3 +3,4 @@",
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
        "@@ -3,3 +3,4 @@",
        " ctxA",
        "-removed1",
        "+added1",
        "+added2",
        " ctxB",
        "@@ -20,1 +21,2 @@",
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

    describe("a deletion-only hunk (@@ -5,3 +4,0 @@): git's zero-count start is the line BEFORE the empty range") {
      val diff = diffOf(
        "@@ -5,3 +4,0 @@",
        "-del1",
        "-del2",
        "-del3",
      )
      val map = DiffLineMap.parse(diff)

      it("classifies the last unaffected line before the deletion as itself") {
        map.classify(4) shouldBe NewLineKind.Unchanged(4)
      }

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

    describe("a hunk whose OLD side lacked a trailing newline") {
      // Git emits the marker right after the line it describes: here the removed old line,
      // since it was the old file's last line and had no trailing newline.
      val diff = diffOf(
        "@@ -1,2 +1,2 @@",
        " first",
        "-second",
        "\\ No newline at end of file",
        "+second-modified",
      )
      val map = DiffLineMap.parse(diff)

      it("does not count the marker line towards the hunk's line counts") {
        map.classify(1) shouldBe NewLineKind.Unchanged(1)
        map.classify(2) shouldBe NewLineKind.Added
      }

      it("classifies a line after the hunk with a zero offset (a miscounted marker would shift this)") {
        map.classify(3) shouldBe NewLineKind.Unchanged(3)
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

    describe("a hunk header omitting line counts: an omitted count means one line") {
      it("accepts an omitted-count hunk whose body has exactly one line per side") {
        val map = DiffLineMap.parse(diffOf("@@ -4 +4 @@", "-old", "+new", "@@ -10 +10 @@", " ctx"))
        map.classify(4) shouldBe NewLineKind.Added
        map.classify(10) shouldBe NewLineKind.Unchanged(10)
      }

      it("rejects an omitted-count hunk whose body consumes two old lines") {
        DiffLineMap.parse(diffOf("@@ -4 +4 @@", " ctx", "-old", "+new")) shouldBe DiffLineMap.Unavailable
      }
    }

    describe("a hunk body that disagrees with its header's counts") {
      it("fails a truncated body (fewer lines than declared) to Unavailable") {
        val diff = diffOf(
          "@@ -3,3 +3,4 @@",
          " ctxA",
          "-removed1",
          "+added1",
        )
        DiffLineMap.parse(diff) shouldBe DiffLineMap.Unavailable
      }

      it("fails a file whose first hunk is short only on the new side to Unavailable") {
        val diff = diffOf(
          "@@ -1,1 +1,2 @@",
          " a",
          "@@ -20,1 +21,1 @@",
          " b",
        )
        DiffLineMap.parse(diff) shouldBe DiffLineMap.Unavailable
      }

      it("fails an over-long body (more lines than declared) to Unavailable") {
        val diff = diffOf(
          "@@ -3,3 +3,4 @@",
          " ctxA",
          "-removed1",
          "+added1",
          "+added2",
          " ctxB",
          " ctxC",
        )
        DiffLineMap.parse(diff) shouldBe DiffLineMap.Unavailable
      }

      it("fails a body with an extra removed line (old side only) to Unavailable") {
        val diff = diffOf("@@ -5,3 +4,0 @@", "-del1", "-del2", "-del3", "-del4")
        DiffLineMap.parse(diff) shouldBe DiffLineMap.Unavailable
      }

      it("still accepts a body that matches its header followed by the trailing newline") {
        DiffLineMap.parse("@@ -1,2 +1,3 @@\n a\n+b\n c\n").classify(3) shouldBe NewLineKind.Unchanged(2)
      }
    }

    describe("out-of-range line numbers") {
      val diff = diffOf(
        "@@ -3,3 +3,4 @@",
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

    describe("a non-empty diff body with no hunk header (e.g. a binary-file notice)") {
      it("parses to Unavailable, not an empty Parsed") {
        val diff = diffOf("Binary files a/x and b/x differ")
        DiffLineMap.parse(diff) shouldBe DiffLineMap.Unavailable
      }
    }

    describe("a hunk body line with an unrecognized prefix") {
      it("fails the whole file to Unavailable instead of silently miscounting later lines") {
        val diff = diffOf(
          "@@ -1,2 +1,2 @@",
          " first",
          "?garbled",
          "+second",
        )
        DiffLineMap.parse(diff) shouldBe DiffLineMap.Unavailable
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
