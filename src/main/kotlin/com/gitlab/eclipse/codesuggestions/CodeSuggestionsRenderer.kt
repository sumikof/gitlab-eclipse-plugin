package com.gitlab.eclipse.codesuggestions

import org.eclipse.swt.custom.StyledText
import org.eclipse.swt.events.PaintEvent
import org.eclipse.swt.events.PaintListener
import org.eclipse.swt.graphics.Color

class CodeSuggestionsRenderer(
  private val textWidget: StyledText,
  private val offset: Int,
  private var text: String,
) : PaintListener {
  companion object {
    private val ghostColor = Color(128, 128, 128)
  }

  init {
    textWidget.addPaintListener(this)
  }

  var lineWithVerticalIndent: Int? = null

  override fun paintControl(paintEvent: PaintEvent) {
    val lines = text.lines()

    renderSuffix(lines, paintEvent)
    if (lines.size > 1) {
      renderBlock(lines.drop(1), paintEvent)
    }
  }

  private fun renderSuffix(lines: List<String>, paintEvent: PaintEvent) {
    val suffix = lines.firstOrNull() ?: return

    val caretPos = textWidget.getLocationAtOffset(offset)

    paintEvent.gc.font = textWidget.font
    paintEvent.gc.foreground = ghostColor

    paintEvent.gc.drawString(suffix, caretPos.x, caretPos.y, true)
  }

  private fun renderBlock(lines: List<String>, paintEvent: PaintEvent) {
    val caretPos = textWidget.getLocationAtOffset(offset)
    val currentLine = textWidget.getLineAtOffset(offset)

    if (currentLine != textWidget.lineCount - 1) {
      val adjustedLine = currentLine + 1
      textWidget.setLineVerticalIndent(adjustedLine, lines.size * textWidget.lineHeight)
    }

    lines.forEachIndexed { index, line ->
      paintEvent.gc.font = textWidget.font
      paintEvent.gc.foreground = ghostColor
      paintEvent.gc.drawString(line, 0, caretPos.y + (index + 1) * textWidget.lineHeight, true)
    }
  }

  fun dispose() {
    textWidget.removePaintListener(this)
    resetVerticalIndent()
  }

  fun update(newText: String) {
    text = newText
    textWidget.redraw()
  }

  private fun resetVerticalIndent() {
    lineWithVerticalIndent?.let { textWidget.setLineVerticalIndent(it, 0) }
    lineWithVerticalIndent = null
  }
}
