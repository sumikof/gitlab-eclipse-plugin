package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.utils.logger
import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.Position
import org.eclipse.swt.custom.StyleRange
import org.eclipse.swt.custom.StyledText
import org.eclipse.swt.events.PaintEvent
import org.eclipse.swt.events.PaintListener
import org.eclipse.swt.graphics.Color
import org.eclipse.swt.graphics.GlyphMetrics
import org.eclipse.swt.graphics.TextLayout

@Suppress("TooManyFunctions")
class CodeSuggestionsRenderer(
  private val document: IDocument,
  private val textWidget: StyledText,
) : PaintListener {
  private val logger by lazy { logger<CodeSuggestionsRenderer>() }

  companion object {
    private val GHOST_COLOR = Color(128, 128, 128)
  }

  init {
    textWidget.addPaintListener(this)
  }

  var offset: Int = -1
    private set

  var text: String? = null
    private set

  private var suggestionCharacterStyle: StyleRange? = null
  private var suggestionLine: Position? = null

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
    paintEvent.gc.foreground = GHOST_COLOR
    paintEvent.gc.drawString(inline, caretPos.x, caretPos.y, true)

    // Redraw the extended character because GlyphMetrics clips it.
    paintEvent.gc.foreground = suggestionCharacterStyle?.foreground ?: textWidget.foreground
    paintEvent.gc.drawString(character, paintEvent.gc.stringExtent(inline).x + characterBounds.x, caretPos.y, true)
  }

  private fun renderSuffix(lines: List<String>, paintEvent: PaintEvent) {
    val suffix = lines.firstOrNull() ?: return

    paintEvent.gc.font = textWidget.font
    paintEvent.gc.foreground = GHOST_COLOR

    val caretPos = textWidget.getLocationAtOffset(offset)
    paintEvent.gc.drawString(suffix, caretPos.x, caretPos.y, true)
  }

  private fun renderBlock(lines: List<String>, paintEvent: PaintEvent) {
    val caretPos = textWidget.getLocationAtOffset(offset)
    val currentLine = textWidget.getLineAtOffset(offset)

    if (suggestionLine == null && currentLine != textWidget.lineCount - 1) {
      textWidget.setLineVerticalIndent(currentLine + 1, lines.size * textWidget.lineHeight)

      // Add a position marker in a document that will be update as new line are created or removed.
      suggestionLine = Position(offset)
      document.addPosition(suggestionLine)
    } else {
      suggestionLine?.let { position ->
        val lineAtOffset = document.getLineOfOffset(position.offset) + 1
        textWidget.setLineVerticalIndent(lineAtOffset, lines.size * textWidget.lineHeight)
      }
    }

    lines.forEachIndexed { index, line ->
      val layout = TextLayout(textWidget.display).apply {
        text = line
        font = textWidget.font
        tabs = textWidget.tabStops
      }

      paintEvent.gc.foreground = GHOST_COLOR
      layout.draw(paintEvent.gc, textWidget.leftMargin, caretPos.y + (index + 1) * textWidget.lineHeight)
      layout.dispose()
    }
  }

  fun display(text: String, offset: Int) {
    this.text = text
    this.offset = offset

    textWidget.redraw()
    textWidget.update()
  }

  fun update(newText: String) {
    display(newText, offset)
  }

  fun reject() {
    clear()

    textWidget.redraw()
    textWidget.update()
  }

  fun dispose() {
    textWidget.removePaintListener(this)
    clear()

    textWidget.redraw()
    textWidget.update()
  }

  fun clear() {
    if (text == null) {
      return
    }

    text = null
    offset = -1

    suggestionLine?.let {
      textWidget.setLineVerticalIndent(document.getLineOfOffset(it.offset) + 1, 0)
      document.removePosition(it)
    }
    suggestionLine = null

    suggestionCharacterStyle?.let { textWidget.setStyleRange(it) }
    suggestionCharacterStyle = null

    textWidget.redraw()
    textWidget.update()
  }

  private fun isEndOfLine(offset: Int): Boolean {
    val line = textWidget.getLineAtOffset(offset)
    val lineEndOffset = textWidget.getOffsetAtLine(line) + textWidget.getLine(line).length

    return offset == lineEndOffset
  }

  fun isCodeSuggestionDisplayed(): Boolean {
    return text != null
  }
}
