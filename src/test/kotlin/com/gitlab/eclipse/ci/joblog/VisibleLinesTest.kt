package com.gitlab.eclipse.ci.joblog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.eclipse.jface.text.Document

/**
 * Review I-1 headless: the range the presenter colors comes from widget lines, so a collapsed
 * section on screen adds nothing but its first line and the margin is counted in lines.
 */
class VisibleLinesTest : DescribeSpec({
  describe("visibleModelLines") {
    it("without folds is the screen plus one screen of lines above and below") {
      val calls = mutableListOf<Int>()

      val runs = visibleModelLines(topLine = 100, bottomLine = 109, lineCount = 1000) {
        calls += it
        it
      }

      runs shouldContainExactly listOf(90..119)
      calls.size shouldBe 30
    }

    it("clamps the margin to the first and last widget line") {
      visibleModelLines(topLine = 0, bottomLine = 9, lineCount = 15) { it } shouldContainExactly listOf(0..14)
    }

    it("leaves the hidden body of a collapsed section out and splits around it") {
      // Model lines 10..50_009 are one section folded to its first line (10): widget line 10 is
      // model line 10 and widget line 11 is model line 50_010.
      val toModel = { widgetLine: Int -> if (widgetLine <= 10) widgetLine else widgetLine + 50_000 - 1 }
      val calls = mutableListOf<Int>()

      val runs = visibleModelLines(topLine = 5, bottomLine = 14, lineCount = 1000) {
        calls += it
        toModel(it)
      }

      runs shouldContainExactly listOf(0..10, 50_010..50_023)
      // Work is bounded by the widget lines, not by the folded text.
      calls.size shouldBe 25
    }

    it("skips widget lines that map to no model line") {
      visibleModelLines(topLine = 3, bottomLine = 3, lineCount = 10) { if (it == 3) -1 else it } shouldContainExactly
        listOf(2..2, 4..4)
    }

    it("is empty for an empty widget") {
      visibleModelLines(topLine = 0, bottomLine = 0, lineCount = 0) { it }.shouldBeEmpty()
    }

    it("treats an inverted screen (bottom above top) as one line") {
      visibleModelLines(topLine = 5, bottomLine = 2, lineCount = 20) { it } shouldContainExactly listOf(4..6)
    }
  }

  describe("modelLineOffsets") {
    val document = Document("a\nbb\nccc\ndddd\n")

    it("spans each run from its first line start to its last line end, delimiter included") {
      modelLineOffsets(document, listOf(0..0, 2..3)) shouldContainExactly listOf(0 until 2, 5 until 14)
    }

    it("drops lines past the end and the empty last line") {
      modelLineOffsets(document, listOf(3..9, 4..4, 7..8)) shouldContainExactly listOf(9 until 14)
    }
  }
})
