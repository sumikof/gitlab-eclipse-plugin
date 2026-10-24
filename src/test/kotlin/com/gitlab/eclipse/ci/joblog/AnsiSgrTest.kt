package com.gitlab.eclipse.ci.joblog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

private data class SgrCase(val name: String, val input: String, val text: String, val runs: List<StyleRun>)

private val red = AnsiStyle(fg = AnsiColor.Named(1))

private val CASES = listOf(
  SgrCase("plain", "hello", "hello", emptyList()),
  SgrCase("red then reset", "\u001B[31mred\u001B[0m ok", "red ok", listOf(StyleRun(0, 3, red))),
  SgrCase(
    "compound 0;33;1",
    "\u001B[0;33;1mwarn",
    "warn",
    listOf(StyleRun(0, 4, AnsiStyle(fg = AnsiColor.Named(3), bold = true))),
  ),
  SgrCase("empty params reset", "\u001B[31ma\u001B[mb", "ab", listOf(StyleRun(0, 1, red))),
  SgrCase("256 cube", "\u001B[38;5;196mx", "x", listOf(StyleRun(0, 1, AnsiStyle(fg = AnsiColor.Rgb(255, 0, 0))))),
  SgrCase("256 named", "\u001B[38;5;9mx", "x", listOf(StyleRun(0, 1, AnsiStyle(fg = AnsiColor.Named(9))))),
  SgrCase("256 gray", "\u001B[48;5;232mx", "x", listOf(StyleRun(0, 1, AnsiStyle(bg = AnsiColor.Rgb(8, 8, 8))))),
  SgrCase("truecolor", "\u001B[38;2;1;2;3mx", "x", listOf(StyleRun(0, 1, AnsiStyle(fg = AnsiColor.Rgb(1, 2, 3))))),
  SgrCase("truecolor bg", "\u001B[48;2;4;5;6mx", "x", listOf(StyleRun(0, 1, AnsiStyle(bg = AnsiColor.Rgb(4, 5, 6))))),
  SgrCase("truncated 38;5", "\u001B[38;5mx", "x", emptyList()),
  SgrCase("truncated 38;2", "\u001B[38;2;1;2mx", "x", emptyList()),
  SgrCase("out of range rgb", "\u001B[38;2;1;2;300mx", "x", emptyList()),
  SgrCase("erase in line removed", "\u001B[0Kcleared", "cleared", emptyList()),
  SgrCase("OSC BEL removed", "a\u001B]8;;http://x\u0007b", "ab", emptyList()),
  SgrCase("OSC ST removed", "a\u001B]0;title\u001B\\b", "ab", emptyList()),
  SgrCase("unterminated OSC to eol", "a\u001B]0;title", "a", emptyList()),
  SgrCase("dangling ESC[", "a\u001B[", "a", emptyList()),
  SgrCase("lone ESC", "a\u001Bb", "ab", emptyList()),
  SgrCase("trailing ESC", "a\u001B", "a", emptyList()),
  SgrCase("two char escape removed", "a\u001BMb", "ab", emptyList()),
  SgrCase("unknown sgr ignored, rest applied", "\u001B[58;31mx", "x", listOf(StyleRun(0, 1, red))),
  SgrCase(
    "22 clears bold and faint",
    "\u001B[1;2mA\u001B[22mB",
    "AB",
    listOf(StyleRun(0, 1, AnsiStyle(bold = true, faint = true))),
  ),
  SgrCase("adjacent same style merged", "\u001B[31ma\u001B[31mb", "ab", listOf(StyleRun(0, 2, red))),
  SgrCase("bright fg", "\u001B[91mx", "x", listOf(StyleRun(0, 1, AnsiStyle(fg = AnsiColor.Named(9))))),
  SgrCase("bright bg", "\u001B[102mx", "x", listOf(StyleRun(0, 1, AnsiStyle(bg = AnsiColor.Named(10))))),
  SgrCase("normal bg", "\u001B[44mx", "x", listOf(StyleRun(0, 1, AnsiStyle(bg = AnsiColor.Named(4))))),
  SgrCase(
    "39 resets fg only",
    "\u001B[1;31ma\u001B[39mb",
    "ab",
    listOf(
      StyleRun(0, 1, AnsiStyle(fg = AnsiColor.Named(1), bold = true)),
      StyleRun(1, 1, AnsiStyle(bold = true)),
    ),
  ),
  SgrCase(
    "49 resets bg only",
    "\u001B[41;1ma\u001B[49mb",
    "ab",
    listOf(
      StyleRun(0, 1, AnsiStyle(bg = AnsiColor.Named(1), bold = true)),
      StyleRun(1, 1, AnsiStyle(bold = true)),
    ),
  ),
  SgrCase("italic", "\u001B[3mx", "x", listOf(StyleRun(0, 1, AnsiStyle(italic = true)))),
  SgrCase("underline", "\u001B[4mx", "x", listOf(StyleRun(0, 1, AnsiStyle(underline = true)))),
  SgrCase("inverse", "\u001B[7mx", "x", listOf(StyleRun(0, 1, AnsiStyle(inverse = true)))),
  SgrCase("strikeout", "\u001B[9mx", "x", listOf(StyleRun(0, 1, AnsiStyle(strikeout = true)))),
  SgrCase("23 clears italic", "\u001B[3ma\u001B[23mb", "ab", listOf(StyleRun(0, 1, AnsiStyle(italic = true)))),
  SgrCase("24 clears underline", "\u001B[4ma\u001B[24mb", "ab", listOf(StyleRun(0, 1, AnsiStyle(underline = true)))),
  SgrCase("27 clears inverse", "\u001B[7ma\u001B[27mb", "ab", listOf(StyleRun(0, 1, AnsiStyle(inverse = true)))),
  SgrCase("29 clears strikeout", "\u001B[9ma\u001B[29mb", "ab", listOf(StyleRun(0, 1, AnsiStyle(strikeout = true)))),
  SgrCase(
    "run offsets follow output text",
    "ab\u001B[31mcd\u001B[0mef\u001B[1mg",
    "abcdefg",
    listOf(StyleRun(2, 2, red), StyleRun(6, 1, AnsiStyle(bold = true))),
  ),
  SgrCase("style without text yields no run", "\u001B[31m\u001B[0mx", "x", emptyList()),
)

class AnsiSgrTest : DescribeSpec({
  describe("parseAnsiLine") {
    withData(nameFn = { it.name }, CASES) { case ->
      val parsed = parseAnsiLine(case.input, AnsiStyle.DEFAULT)
      parsed.text shouldBe case.text
      parsed.runs shouldBe case.runs
    }

    it("carries the initial style over into the line") {
      val parsed = parseAnsiLine("x", red)
      parsed.text shouldBe "x"
      parsed.runs shouldBe listOf(StyleRun(0, 1, red))
      parsed.endStyle shouldBe red
    }

    it("reports the style in effect at the end of the line") {
      parseAnsiLine("\u001B[1mx\u001B[31m", AnsiStyle.DEFAULT).endStyle shouldBe
        AnsiStyle(fg = AnsiColor.Named(1), bold = true)
    }

    it("ends with the default style after a reset") {
      val parsed = parseAnsiLine("\u001B[31mx\u001B[0m", AnsiStyle.DEFAULT)
      parsed.endStyle shouldBe AnsiStyle.DEFAULT
      parsed.endStyle.isDefault shouldBe true
    }

    it("lets a reset cancel an initial style") {
      val parsed = parseAnsiLine("a\u001B[0mb", red)
      parsed.runs shouldBe listOf(StyleRun(0, 1, red))
      parsed.endStyle shouldBe AnsiStyle.DEFAULT
    }

    it("never leaves an ESC in the text") {
      val parsed = parseAnsiLine("\u001B[38;5m\u001B[\u001B]8;;\u001B", AnsiStyle.DEFAULT)
      parsed.text shouldBe ""
    }
  }
})
