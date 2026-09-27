package com.gitlab.eclipse.mergerequests.review

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

private fun diffOf(vararg lines: String) = lines.joinToString("\n")

private val REFS = VersionRefs(baseSha = "base1", startSha = "start1", headSha = "head1")

// classify(1..3) => Unchanged(1..3), classify(4..5) => Added, classify(6) => Unchanged(5) (past the
// hunk, via cumulative offset), classify(7) => Unchanged(6). Document has 7 lines.
private val MAP = DiffLineMap.parse(
  diffOf(
    "@@ -3,3 +3,4 @@",
    " ctxA",
    "-removed1",
    "+added1",
    "+added2",
    " ctxB",
  ),
)

private fun build(
  oneBasedLine: Int,
  lineCount: Int,
  map: DiffLineMap = MAP,
  oldPath: String = "a.txt",
  newPath: String = "a.txt",
) = buildPosition(REFS, oldPath, newPath, map, oneBasedLine, lineCount)

class DiffPositionBuilderTest : DescribeSpec({
  describe("buildPosition") {
    it("builds an added line without an oldLine key") {
      val result = build(oneBasedLine = 4, lineCount = 7)

      result shouldBe PositionResult.Ready(
        mapOf(
          "baseSha" to "base1",
          "headSha" to "head1",
          "startSha" to "start1",
          "paths" to mapOf("oldPath" to "a.txt", "newPath" to "a.txt"),
          "newLine" to 4,
        ),
      )
      (result as PositionResult.Ready).variables.containsKey("oldLine") shouldBe false
    }

    it("builds an unchanged line with an oldLine key") {
      val result = build(oneBasedLine = 3, lineCount = 7)

      result shouldBe PositionResult.Ready(
        mapOf(
          "baseSha" to "base1",
          "headSha" to "head1",
          "startSha" to "start1",
          "paths" to mapOf("oldPath" to "a.txt", "newPath" to "a.txt"),
          "newLine" to 3,
          "oldLine" to 3,
        ),
      )
    }

    it("builds an unchanged line past the hunk (cumulative-offset fallback) with the shifted oldLine") {
      val result = build(oneBasedLine = 6, lineCount = 7)

      result shouldBe PositionResult.Ready(
        mapOf(
          "baseSha" to "base1",
          "headSha" to "head1",
          "startSha" to "start1",
          "paths" to mapOf("oldPath" to "a.txt", "newPath" to "a.txt"),
          "newLine" to 6,
          "oldLine" to 5,
        ),
      )
    }

    it("builds the document's first line") {
      val result = build(oneBasedLine = 1, lineCount = 7)

      result shouldBe PositionResult.Ready(
        mapOf(
          "baseSha" to "base1",
          "headSha" to "head1",
          "startSha" to "start1",
          "paths" to mapOf("oldPath" to "a.txt", "newPath" to "a.txt"),
          "newLine" to 1,
          "oldLine" to 1,
        ),
      )
    }

    it("builds the document's last line") {
      val result = build(oneBasedLine = 7, lineCount = 7)

      result shouldBe PositionResult.Ready(
        mapOf(
          "baseSha" to "base1",
          "headSha" to "head1",
          "startSha" to "start1",
          "paths" to mapOf("oldPath" to "a.txt", "newPath" to "a.txt"),
          "newLine" to 7,
          "oldLine" to 6,
        ),
      )
    }

    it("builds a line under DiffLineMap.Identity as unchanged with the same old/new line number") {
      val result = build(
        oneBasedLine = 5,
        lineCount = 10,
        map = DiffLineMap.Identity,
        oldPath = "old.txt",
        newPath = "new.txt",
      )

      result shouldBe PositionResult.Ready(
        mapOf(
          "baseSha" to "base1",
          "headSha" to "head1",
          "startSha" to "start1",
          "paths" to mapOf("oldPath" to "old.txt", "newPath" to "new.txt"),
          "newLine" to 5,
          "oldLine" to 5,
        ),
      )
    }

    it("refuses DiffLineMap.Unavailable with DIFF_UNAVAILABLE") {
      val result = build(oneBasedLine = 3, lineCount = 7, map = DiffLineMap.Unavailable)

      result shouldBe PositionResult.Refused(RefuseReason.DIFF_UNAVAILABLE)
    }

    it("refuses line 0 with LINE_OUT_OF_RANGE, even against DiffLineMap.Unavailable") {
      val result = build(oneBasedLine = 0, lineCount = 7, map = DiffLineMap.Unavailable)

      result shouldBe PositionResult.Refused(RefuseReason.LINE_OUT_OF_RANGE)
    }

    it("refuses line lineCount + 1 with LINE_OUT_OF_RANGE") {
      val result = build(oneBasedLine = 8, lineCount = 7)

      result shouldBe PositionResult.Refused(RefuseReason.LINE_OUT_OF_RANGE)
    }

    it("uses distinct oldPath and newPath for a rename") {
      val result = build(oneBasedLine = 4, lineCount = 7, oldPath = "old-name.txt", newPath = "new-name.txt")

      val expectedPaths = mapOf("oldPath" to "old-name.txt", "newPath" to "new-name.txt")
      (result as PositionResult.Ready).variables["paths"] shouldBe expectedPaths
    }
  }
})
