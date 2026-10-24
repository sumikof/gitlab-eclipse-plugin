package com.gitlab.eclipse.ci.joblog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

private const val DEFAULT_FG = 0x112233
private const val DEFAULT_BG = 0xFFFFFF

private val DARK = listOf(
  0x000000, 0xC91B00, 0x00A600, 0xC7C400, 0x0225C7, 0xCA30C7, 0x00C5C7, 0xC7C7C7,
  0x686868, 0xFF6E67, 0x5FFA68, 0xFFFC67, 0x6871FF, 0xFF77FF, 0x60FDFF, 0xFFFFFF,
)

private val LIGHT = DARK.toMutableList().apply {
  this[3] = 0x9A7B00
  this[7] = 0x6E6E6E
  this[11] = 0xB58900
  this[14] = 0x0098A6
  this[15] = 0x3A3A3A
}

private fun resolve(style: AnsiStyle, dark: Boolean = true) = resolveStyle(style, dark, DEFAULT_FG, DEFAULT_BG)

class AnsiPaletteTest : DescribeSpec({
  describe("resolveStyle") {
    it("maps every dark named color to the dark palette") {
      DARK.forEachIndexed { i, rgb ->
        resolve(AnsiStyle(fg = AnsiColor.Named(i)), dark = true).fgRgb shouldBe rgb
        resolve(AnsiStyle(bg = AnsiColor.Named(i)), dark = true).bgRgb shouldBe rgb
      }
    }

    it("maps every light named color to the light palette") {
      LIGHT.forEachIndexed { i, rgb ->
        resolve(AnsiStyle(fg = AnsiColor.Named(i)), dark = false).fgRgb shouldBe rgb
      }
    }

    it("keeps Rgb colors as they are") {
      val style = AnsiStyle(fg = AnsiColor.Rgb(0x10, 0x20, 0x30), bg = AnsiColor.Rgb(1, 2, 3))
      resolve(style).fgRgb shouldBe 0x102030
      resolve(style, dark = false).bgRgb shouldBe 0x010203
    }

    it("leaves colors null for the default style") {
      val r = resolve(AnsiStyle.DEFAULT)
      r.fgRgb shouldBe null
      r.bgRgb shouldBe null
    }

    it("carries the text attributes") {
      val r = resolve(AnsiStyle(bold = true, italic = true, underline = true, strikeout = true))
      r.bold shouldBe true
      r.italic shouldBe true
      r.underline shouldBe true
      r.strikeout shouldBe true
    }

    it("swaps fg and bg when inverse") {
      val r = resolve(AnsiStyle(fg = AnsiColor.Named(1), bg = AnsiColor.Named(2), inverse = true))
      r.fgRgb shouldBe 0x00A600
      r.bgRgb shouldBe 0xC91B00
    }

    it("fills a missing side with the editor default when inverse") {
      val r = resolve(AnsiStyle(fg = AnsiColor.Named(1), inverse = true))
      r.fgRgb shouldBe DEFAULT_BG
      r.bgRgb shouldBe 0xC91B00
    }

    it("uses the defaults for both sides when inverse without colors") {
      val r = resolve(AnsiStyle(inverse = true))
      r.fgRgb shouldBe DEFAULT_BG
      r.bgRgb shouldBe DEFAULT_FG
    }

    it("averages fg with bg when faint") {
      val style = AnsiStyle(fg = AnsiColor.Rgb(200, 100, 0), bg = AnsiColor.Rgb(0, 100, 100), faint = true)
      resolve(style).fgRgb shouldBe 0x646432
    }

    it("averages the default fg with the default bg when faint without colors") {
      resolve(AnsiStyle(faint = true)).fgRgb shouldBe 0x889099
    }
  }
})
