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

  var position: Position? = null
    private set

  var text: String? = null
    private set

  private var suggestionCharacterStyle: StyleRange? = null

  override fun paintControl(paintEvent: PaintEvent) {
    try {
      val lines = text?.lines()
        ?: return

      val offset = position?.offset
        ?: return

      if (isEndOfLine(offset)) {
        renderSuffix(lines, offset, paintEvent)
      } else {
        renderInline(lines, offset, paintEvent)
      }

      if (lines.size > 1) {
        renderBlock(lines.drop(1), offset, paintEvent)
      }
    } catch (e: Exception) {
      logger.error("Error rendering code suggestion.", e)
    }
  }

  private fun renderInline(lines: List<String>, offset: Int, paintEvent: PaintEvent) {
    val inline = lines.firstOrNull() ?: return

    val character = textWidget.getText(offset, offset)
    val characterBounds = textWidget.getTextBounds(offset, offset)

    val characterStyleRange = textWidget.getStyleRangeAtOffset(offset)
    if (suggestionCharacterStyle == null) {
      characterStyleRange.metrics = GlyphMetrics(
        paintEvent.gc.fontMetrics.ascent,
        paintEvent.gc.fontMetrics.descent,
        paintEvent.gc.stringExtent(inline).x + paintEvent.gc.stringExtent(character).x
      )

      suggestionCharacterStyle = characterStyleRange
    }

    textWidget.setStyleRange(characterStyleRange)

    val caretPos = textWidget.getLocationAtOffset(offset)
    paintEvent.gc.font = textWidget.font
    paintEvent.gc.foreground = GHOST_COLOR
    paintEvent.gc.drawString(inline, caretPos.x, caretPos.y, true)

    // Redraw the extended character because GlyphMetrics clips it.
    paintEvent.gc.foreground = suggestionCharacterStyle?.foreground ?: textWidget.foreground
    paintEvent.gc.drawString(character, paintEvent.gc.stringExtent(inline).x + characterBounds.x, caretPos.y, true)
  }

  private fun renderSuffix(lines: List<String>, offset: Int, paintEvent: PaintEvent) {
    val suffix = lines.firstOrNull() ?: return

    paintEvent.gc.font = textWidget.font
    paintEvent.gc.foreground = GHOST_COLOR

    val caretPos = textWidget.getLocationAtOffset(offset)
    paintEvent.gc.drawString(suffix, caretPos.x, caretPos.y, true)
  }

  private fun renderBlock(lines: List<String>, offset: Int, paintEvent: PaintEvent) {
    val caretPos = textWidget.getLocationAtOffset(offset)
    val currentLine = textWidget.getLineAtOffset(offset)

    if (currentLine != textWidget.lineCount - 1) {
      textWidget.setLineSpacingProvider { lineIndex ->
        when {
          lineIndex == currentLine -> lines.size * textWidget.lineHeight
          else -> null
        }
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
    this.position = Position(offset)

    document.addPosition(position)

    textWidget.redrawNow()
  }

  fun update(newText: String) {
    this.text = newText

    textWidget.redrawNow()
  }

  fun reject() {
    clear()
    textWidget.redrawNow()
  }

  fun dispose() {
    textWidget.removePaintListener(this)
    clear()
    textWidget.redrawNow()
  }

  fun clear() {
    suggestionCharacterStyle?.let { style ->
      style.metrics = GlyphMetrics(0, 0, 0)
      textWidget.setStyleRange(style)
    }

    textWidget.setLineSpacingProvider(null)
    document.removePosition(position)

    text = null
    position = null
    suggestionCharacterStyle = null
  }

  fun isCodeSuggestionDisplayed(): Boolean {
    return text != null
  }

  private fun isEndOfLine(offset: Int): Boolean {
    val line = textWidget.getLineAtOffset(offset)
    val lineEndOffset = textWidget.getOffsetAtLine(line) + textWidget.getLine(line).length

    return offset == lineEndOffset
  }

  private fun StyledText.redrawNow() {
    redraw()
    update()
  }
}
