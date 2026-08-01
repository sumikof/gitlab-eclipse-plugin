package com.gitlab.eclipse.ci.joblog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class TraceFormatterTest : DescribeSpec({
  describe("stripTraceFormatting") {
    it("returns empty string for empty input (identity)") {
      stripTraceFormatting("") shouldBe ""
    }

    it("returns plain text unchanged (identity)") {
      stripTraceFormatting("hello world") shouldBe "hello world"
    }

    it("preserves multiline text and tabs (identity)") {
      stripTraceFormatting("line1\n\tindented\nline3") shouldBe "line1\n\tindented\nline3"
    }

    it("preserves a trailing newline") {
      stripTraceFormatting("a\n") shouldBe "a\n"
    }

    it("normalizes CRLF to LF") {
      stripTraceFormatting("a\r\nb\r\nc") shouldBe "a\nb\nc"
    }

    it("removes SGR color escapes") {
      stripTraceFormatting("\u001B[32mgreen\u001B[0m text") shouldBe "green text"
    }

    it("removes compound SGR param escapes") {
      stripTraceFormatting("\u001B[0;33;1mwarn\u001B[0;m") shouldBe "warn"
    }

    it("removes erase-in-line escapes") {
      stripTraceFormatting("\u001B[0Kcleared") shouldBe "cleared"
    }

    it("keeps only text after the last bare-CR progress overwrite") {
      stripTraceFormatting("Downloading 10%\rDownloading 50%\rDone") shouldBe "Done"
    }

    it("resolves a real section_start line (CR + ANSI)") {
      stripTraceFormatting(
        "section_start:1699999999:build_script\r\u001B[0K\u001B[32;1m\$ echo hi\u001B[0;m",
      ) shouldBe "\$ echo hi"
    }

    it("collapses a real section_end line to empty") {
      stripTraceFormatting("section_end:1699999999:build_script\r\u001B[0K") shouldBe ""
    }

    it("defensively removes a section token with no trailing CR") {
      stripTraceFormatting("section_start:123:step_script") shouldBe ""
    }

    it("strips ANSI before control-char stripping (no stray bracket/digit residue)") {
      stripTraceFormatting("\u001B[31mred\u001B[0m") shouldBe "red"
    }

    it("strips non-ANSI control chars but keeps tab") {
      stripTraceFormatting("a\u0000b\u0008c\td") shouldBe "abc\td"
    }

    it("handles a combined multiline trace with section markers and ANSI color") {
      val input =
        "section_start:1:prepare\r\u001B[0KPreparing\n" +
          "Running \u001B[32mtest\u001B[0m\n" +
          "section_end:1:prepare\r\u001B[0K"

      stripTraceFormatting(input) shouldBe "Preparing\nRunning test\n"
    }
  }
})
