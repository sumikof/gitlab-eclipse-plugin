package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.utils.logger
import org.eclipse.swt.custom.StyleRange
import org.eclipse.swt.custom.StyledText
import org.eclipse.swt.events.PaintEvent
import org.eclipse.swt.events.PaintListener
import org.eclipse.swt.graphics.Color
import org.eclipse.swt.graphics.GlyphMetrics

class CodeSuggestionsRenderer(
  private val textWidget: StyledText,
) : PaintListener {
  private val logger by lazy { logger<CodeSuggestionsRenderer>() }

  companion object {
    private val ghostColor = Color(128, 128, 128)
  }

  init {
    textWidget.addPaintListener(this)
  }

  private var offset: Int = -1
  private var text: String? = null

  private var suggestionCharacterStyle: StyleRange? = null
  private var suggestionLine: Int? = null

  override fun paintControl(paintEvent: PaintEvent) {
    try {
      val lines = text?.lines()
        ?: return

      if (isEndOfLine(offset)) {
        renderSuffix(lines, paintEvent)
      } else {
        renderInline(lines, paintEvent)
      }

      if (lines.size > 1) {
        renderBlock(lines.drop(1), paintEvent)
      }
    } catch (e: Exception) {
      logger.error("Error rendering code suggestion.", e)
    }
  }

  private fun renderInline(lines: List<String>, paintEvent: PaintEvent) {
    val inline = lines.firstOrNull() ?: return

    val character = textWidget.getText(offset, offset)
    val characterBounds = textWidget.getTextBounds(offset, offset)

    if (suggestionCharacterStyle == null) {
      suggestionCharacterStyle = textWidget.getStyleRangeAtOffset(offset)
    }

    textWidget.setStyleRange(
      StyleRange().apply {
        start = offset
        length = 1
        foreground = suggestionCharacterStyle?.foreground
        metrics = GlyphMetrics(
          paintEvent.gc.fontMetrics.ascent,
          paintEvent.gc.fontMetrics.descent,
          paintEvent.gc.stringExtent(inline).x + paintEvent.gc.stringExtent(character).x
        )
      }
    )

    val caretPos = textWidget.getLocationAtOffset(offset)
    paintEvent.gc.font = textWidget.font
    paintEvent.gc.foreground = ghostColor
    paintEvent.gc.drawString(inline, caretPos.x, caretPos.y, true)

    // Redraw the extended character because GlyphMetrics clips it.
    paintEvent.gc.foreground = suggestionCharacterStyle?.foreground ?: textWidget.foreground
    paintEvent.gc.drawString(character, paintEvent.gc.stringExtent(inline).x + characterBounds.x, caretPos.y, true)
  }

  private fun renderSuffix(lines: List<String>, paintEvent: PaintEvent) {
    val suffix = lines.firstOrNull() ?: return

    paintEvent.gc.font = textWidget.font
    paintEvent.gc.foreground = ghostColor

    val caretPos = textWidget.getLocationAtOffset(offset)
    paintEvent.gc.drawString(suffix, caretPos.x, caretPos.y, true)
  }

  private fun renderBlock(lines: List<String>, paintEvent: PaintEvent) {
    val caretPos = textWidget.getLocationAtOffset(offset)
    val currentLine = textWidget.getLineAtOffset(offset)

    if (currentLine != textWidget.lineCount - 1) {
      val adjustedLine = currentLine + 1
      textWidget.setLineVerticalIndent(adjustedLine, lines.size * textWidget.lineHeight)
      suggestionLine = adjustedLine
    }

    lines.forEachIndexed { index, line ->
      paintEvent.gc.font = textWidget.font
      paintEvent.gc.foreground = ghostColor
      paintEvent.gc.drawString(line, 0, caretPos.y + (index + 1) * textWidget.lineHeight, true)
    }
  }

  fun display(text: String, offset: Int) {
    this.text = text
    this.offset = offset

    textWidget.redraw()
  }

  fun dispose() {
    textWidget.removePaintListener(this)
    clear()
  }

  fun clear() {
    text = null
    offset = -1

    suggestionLine?.let { textWidget.setLineVerticalIndent(it, 0) }
    suggestionLine = null

    suggestionCharacterStyle?.let { textWidget.setStyleRange(it) }
    suggestionCharacterStyle = null

    textWidget.redraw()
  }

  private fun isEndOfLine(offset: Int): Boolean {
    val line = textWidget.getLineAtOffset(offset)
    val lineEndOffset = textWidget.getOffsetAtLine(line) + textWidget.getLine(line).length

    return offset == lineEndOffset
  }
}
