package com.gitlab.eclipse.views.inlinethread

/** A point in display coordinates. */
data class ScreenPoint(val x: Int, val y: Int)

/** A rectangle in display coordinates. */
data class ScreenRect(val x: Int, val y: Int, val width: Int, val height: Int)

/** One visible text line in display coordinates: its left edge, its top pixel and its height. */
data class LineBox(val left: Int, val top: Int, val height: Int)

/**
 * SWT-free placement of the inline thread popup (design §6.4 E8, §8.2): directly below the
 * anchored line when it is visible, else at the top center of the editor area, and always inside
 * the client area. The SWT layer supplies the widget line, the pixel lookups and the areas; every
 * decision is here so it can be tested headless.
 */
object PopupAnchor {
  /**
   * The line's box, or `null` when the line is not on screen: [widgetLine] is negative —
   * `JFaceTextUtil.modelLineToWidgetLine` reports a hidden (folded / projected-away) line as -1,
   * and `StyledText.getLinePixel` would silently clamp that to line 0 (E8), so [linePixel] is not
   * consulted at all then — or its pixel lies outside the widget's viewport of [viewportHeight]
   * (scrolled away). The caller reveals the line and asks again.
   */
  fun lineBox(
    widgetLine: Int,
    lineHeight: Int,
    linePixel: (Int) -> Int,
    toDisplay: (Int, Int) -> ScreenPoint,
    viewportHeight: Int = Int.MAX_VALUE,
  ): LineBox? {
    if (widgetLine < 0) return null
    val pixel = linePixel(widgetLine)
    if (pixel < 0 || pixel >= viewportHeight) return null
    val origin = toDisplay(0, pixel)
    return LineBox(left = origin.x, top = origin.y, height = lineHeight)
  }

  /**
   * The popup rectangle for a popup of [width] × [height]: under [line] (left-aligned with it), or
   * at the top center of [editorArea] when the line is not visible, then clamped into
   * [clientArea] — shifted left / up when it overflows, and shrunk when it is larger than the
   * client area itself.
   */
  fun place(line: LineBox?, editorArea: ScreenRect, width: Int, height: Int, clientArea: ScreenRect): ScreenRect {
    val desiredX = line?.left ?: (editorArea.x + (editorArea.width - width) / 2)
    val desiredY = line?.let { it.top + it.height } ?: editorArea.y
    val w = minOf(width, clientArea.width)
    val h = minOf(height, clientArea.height)
    val x = desiredX.coerceAtMost(clientArea.x + clientArea.width - w).coerceAtLeast(clientArea.x)
    val y = desiredY.coerceAtMost(clientArea.y + clientArea.height - h).coerceAtLeast(clientArea.y)
    return ScreenRect(x, y, w, h)
  }
}
