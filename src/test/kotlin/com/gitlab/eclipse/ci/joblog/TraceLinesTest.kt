package com.gitlab.eclipse.ci.joblog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

private data class LineCase(val name: String, val input: String, val text: String, val events: List<SectionEvent>)

private const val ESC_K = "\u001B[0K"

private val LINE_CASES = listOf(
  LineCase("plain", "hello", "hello", emptyList()),
  LineCase("empty", "", "", emptyList()),
  LineCase("keeps ANSI", "\u001B[31mred\u001B[0m", "\u001B[31mred\u001B[0m", emptyList()),
  LineCase("last CR wins", "Downloading 10%\rDownloading 50%\rDone", "Done", emptyList()),
  LineCase("trailing CR from CRLF", "abc\r", "abc", emptyList()),
  LineCase(
    "section_start with CR and ANSI",
    "section_start:1699999999:build_script\r${ESC_K}Building",
    "${ESC_K}Building",
    listOf(SectionEvent.Start("build_script", false)),
  ),
  LineCase(
    "section_start collapsed",
    "section_start:1:prep[collapsed=true]\r$ESC_K",
    ESC_K,
    listOf(SectionEvent.Start("prep", true)),
  ),
  LineCase(
    "collapsed among other options",
    "section_start:1:prep[hide_duration=true,collapsed=true]",
    "",
    listOf(SectionEvent.Start("prep", true)),
  ),
  LineCase(
    "collapsed=false and unknown options",
    "section_start:1:prep[collapsed=false,foo=bar]",
    "",
    listOf(SectionEvent.Start("prep", false)),
  ),
  LineCase("section_end", "section_end:1:prep\r$ESC_K", ESC_K, listOf(SectionEvent.End("prep"))),
  LineCase(
    "section_start without CR is still removed",
    "section_start:123:step_script",
    "",
    listOf(SectionEvent.Start("step_script", false)),
  ),
  LineCase("name may contain non-word characters", "section_end:1:a/b:c\r", "", listOf(SectionEvent.End("a/b:c"))),
  LineCase("marker after the last CR is not detected", "text\rsection_end:1:a", "", emptyList()),
  LineCase("strips C0 but keeps tab", "a\u0000b\u0008c\td", "abc\td", emptyList()),
  LineCase("strips VT and FF", "a\u000Bb\u000Cc", "abc", emptyList()),
  LineCase("keeps BEL for the OSC terminator", "a\u0007b", "a\u0007b", emptyList()),
  LineCase("name stops at whitespace", "section_start:1:a b", " b", listOf(SectionEvent.Start("a", false))),
  LineCase("strips DEL", "x\u007Fy", "xy", emptyList()),
  LineCase("keeps ESC for the ANSI parser", "\u001B[32;1mhi\u001B[0;m", "\u001B[32;1mhi\u001B[0;m", emptyList()),
)

private const val TS = "2024-01-01T00:00:00.000000Z"

class TraceLinesTest : DescribeSpec({
  describe("normalizeTraceLine") {
    withData(nameFn = { it.name }, LINE_CASES) { case ->
      val info = normalizeTraceLine(case.input)
      info.text shouldBe case.text
      info.events shouldBe case.events
    }
  }

  describe("normalizeTraceLine followed by parseAnsiLine") {
    fun visible(line: String) = parseAnsiLine(normalizeTraceLine(line).text, AnsiStyle.DEFAULT).text

    it("drops an OSC hyperlink but keeps its label") {
      visible("see \u001B]8;;http://x\u0007link\u001B]8;;\u0007 done") shouldBe "see link done"
    }

    it("drops an OSC title") {
      visible("\u001B]0;title\u0007text") shouldBe "text"
    }
  }

  describe("detectTimestampMode") {
    it("detects an ISO-8601 instant header") {
      detectTimestampMode("$TS 00O hello") shouldBe TimestampMode.TIMESTAMPED
    }

    it("detects a header with an offset") {
      detectTimestampMode("2024-01-01T00:00:00+09:00 00O x") shouldBe TimestampMode.TIMESTAMPED
    }

    it("rejects plain text") {
      detectTimestampMode("Running with gitlab-runner") shouldBe TimestampMode.NONE
    }

    it("rejects an empty line") {
      detectTimestampMode("") shouldBe TimestampMode.NONE
    }

    it("rejects a line without a space") {
      detectTimestampMode(TS) shouldBe TimestampMode.NONE
    }

    it("rejects a date-looking word that is not a full timestamp") {
      detectTimestampMode("2024-01-01 00O x") shouldBe TimestampMode.NONE
    }
  }

  describe("stripTimestampHeader") {
    it("removes the header and does not append") {
      stripTimestampHeader("$TS 00O hello") shouldBe ("hello" to false)
    }

    it("flags a continuation line") {
      stripTimestampHeader("$TS 00O+rest") shouldBe ("rest" to true)
    }

    it("keeps the body's own leading spaces") {
      stripTimestampHeader("$TS 01E   indented") shouldBe ("  indented" to false)
    }

    it("returns an empty body for a header-only line") {
      stripTimestampHeader("$TS 00O ") shouldBe ("" to false)
    }

    it("copes with a line shorter than the header") {
      stripTimestampHeader("short") shouldBe ("" to false)
    }
  }
})
