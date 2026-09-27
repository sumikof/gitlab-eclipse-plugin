package com.gitlab.eclipse.views.inlinethread

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private val EDITOR = ScreenRect(x = 100, y = 50, width = 800, height = 600)
private val CLIENT = ScreenRect(x = 0, y = 0, width = 1920, height = 1080)
private const val W = 400
private const val H = 300

class PopupAnchorTest : DescribeSpec({
  describe("lineBox (E8)") {
    it("returns null for a hidden line (-1) and never asks StyledText for its pixel, which would clamp -1 to line 0") {
      var pixelCalls = 0
      val box = PopupAnchor.lineBox(
        widgetLine = -1,
        lineHeight = 17,
        linePixel = {
          pixelCalls++
          0
        },
        toDisplay = { x, y -> ScreenPoint(x, y) },
      )
      box.shouldBeNull()
      pixelCalls shouldBe 0
    }

    it("maps the first widget line (0) through linePixel and toDisplay and keeps the line height") {
      var asked = -1
      val box = PopupAnchor.lineBox(
        widgetLine = 0,
        lineHeight = 17,
        linePixel = { line ->
          asked = line
          5
        },
        toDisplay = { x, y -> ScreenPoint(x + 1000, y + 2000) },
      )
      asked shouldBe 0
      box shouldBe LineBox(left = 1000, top = 2005, height = 17)
    }

    it("maps a later widget line the same way") {
      val box = PopupAnchor.lineBox(7, 20, { it * 20 }, { x, y -> ScreenPoint(x + 10, y + 100) })
      box shouldBe LineBox(left = 10, top = 240, height = 20)
    }

    it("treats a line scrolled above or below the viewport as not visible") {
      val toDisplay = { x: Int, y: Int -> ScreenPoint(x, y) }
      PopupAnchor.lineBox(3, 20, { -40 }, toDisplay, viewportHeight = 500).shouldBeNull()
      PopupAnchor.lineBox(3, 20, { 500 }, toDisplay, viewportHeight = 500).shouldBeNull()
      PopupAnchor.lineBox(3, 20, { 499 }, toDisplay, viewportHeight = 500) shouldBe LineBox(0, 499, 20)
      PopupAnchor.lineBox(3, 20, { 0 }, toDisplay, viewportHeight = 500) shouldBe LineBox(0, 0, 20)
    }
  }

  describe("place") {
    it("puts the popup directly below the line, left-aligned with it") {
      val rect = PopupAnchor.place(LineBox(left = 300, top = 400, height = 18), EDITOR, W, H, CLIENT)
      rect shouldBe ScreenRect(x = 300, y = 418, width = W, height = H)
    }

    it("falls back to the top center of the editor area when the line has no box") {
      val rect = PopupAnchor.place(null, EDITOR, W, H, CLIENT)
      rect shouldBe ScreenRect(x = 100 + (800 - W) / 2, y = 50, width = W, height = H)
    }

    it("shifts the popup left when it would overflow the right edge of the client area") {
      val rect = PopupAnchor.place(LineBox(left = 1800, top = 100, height = 18), EDITOR, W, H, CLIENT)
      rect shouldBe ScreenRect(x = 1920 - W, y = 118, width = W, height = H)
    }

    it("shifts the popup up when it would overflow the bottom edge of the client area") {
      val rect = PopupAnchor.place(LineBox(left = 300, top = 1000, height = 18), EDITOR, W, H, CLIENT)
      rect shouldBe ScreenRect(x = 300, y = 1080 - H, width = W, height = H)
    }

    it("never starts left of or above the client area (multi-monitor origins can be negative)") {
      val client = ScreenRect(x = -1920, y = -200, width = 1920, height = 1080)
      val rect = PopupAnchor.place(LineBox(left = -2000, top = -300, height = 18), EDITOR, W, H, client)
      rect shouldBe ScreenRect(x = -1920, y = -200, width = W, height = H)
    }

    it("shrinks a popup larger than the client area to the client area itself") {
      val client = ScreenRect(x = 10, y = 20, width = 300, height = 200)
      val rect = PopupAnchor.place(LineBox(left = 50, top = 60, height = 18), EDITOR, W, H, client)
      rect shouldBe client
    }

    it("keeps a fitting fallback rectangle unchanged") {
      val rect = PopupAnchor.place(null, ScreenRect(0, 0, 1000, 500), 200, 100, CLIENT)
      rect shouldBe ScreenRect(x = 400, y = 0, width = 200, height = 100)
    }
  }
})
