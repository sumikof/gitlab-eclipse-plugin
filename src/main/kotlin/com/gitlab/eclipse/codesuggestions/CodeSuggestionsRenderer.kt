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
import org.eclipse.swt.graphics.Point
import org.eclipse.swt.graphics.TextLayout

@Suppress("TooManyFunctions")
class CodeSuggestionsRenderer(
  private val document: IDocument,
  private val textWidget: StyledText
) : PaintListener {
  private val logger by lazy { logger<CodeSuggestionsRenderer>() }

  companion object {
    private val GHOST_COLOR = Color(128, 128, 128)
  }

  init {
    textWidget.addPaintListener(this)
  }

  var documentPosition: Position? = null
    private set

  var text: String? = null
    private set

  private var height: Int? = null
  private var suggestionCharacterStyle: StyleRange? = null

  override fun paintControl(paintEvent: PaintEvent) {
    try {
      val lines = text?.lines()
        ?: return

      val offset = documentPosition?.offset
        ?: return

      val position = textWidget.caret.location
        ?: return

      if (isEndOfLine(offset)) {
        renderSuffix(lines, position, paintEvent)
      } else {
        renderInline(lines, offset, position, paintEvent)
      }

      if (lines.size > 1) {
        renderBlock(lines.drop(1), position, paintEvent)
        height?.let { textWidget.setLineVerticalIndent(textWidget.getLineAtOffset(offset) + 1, it) }
      } else {
        textWidget.setLineVerticalIndent(textWidget.getLineAtOffset(offset) + 1, 0)
      }
    } catch (e: Exception) {
      logger.error("Error rendering code suggestion.", e)
    }
  }

  private fun renderInline(lines: List<String>, offset: Int, position: Point, paintEvent: PaintEvent) {
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

    paintEvent.gc.font = textWidget.font
    paintEvent.gc.foreground = GHOST_COLOR
    paintEvent.gc.drawString(inline, position.x, position.y, true)

    // Redraw the extended character because GlyphMetrics clips it.
    paintEvent.gc.foreground = suggestionCharacterStyle?.foreground ?: textWidget.foreground
    paintEvent.gc.drawString(character, paintEvent.gc.stringExtent(inline).x + characterBounds.x, position.y, true)
  }

  private fun renderSuffix(lines: List<String>, position: Point, paintEvent: PaintEvent) {
    val suffix = lines.firstOrNull() ?: return

    val layout = TextLayout(textWidget.display).apply {
      text = suffix
      font = textWidget.font
      tabs = textWidget.tabStops
    }
    paintEvent.gc.foreground = GHOST_COLOR
    layout.draw(paintEvent.gc, position.x, position.y)
    layout.dispose()
  }

  private fun renderBlock(lines: List<String>, position: Point, paintEvent: PaintEvent) {
    lines.forEachIndexed { index, line ->
      val layout = TextLayout(textWidget.display).apply {
        text = line
        font = textWidget.font
        tabs = textWidget.tabStops
      }

      paintEvent.gc.foreground = GHOST_COLOR
      layout.draw(paintEvent.gc, textWidget.leftMargin, position.y + (index + 1) * textWidget.lineHeight)
      layout.dispose()
    }
  }

  fun display(text: String, offset: Int) {
    document.removePosition(documentPosition)

    this.text = text
    this.documentPosition = Position(offset)
    setHeight(text.lines().size)

    document.addPosition(documentPosition)

    textWidget.redrawNow()
  }

  fun update(newText: String) {
    this.text = newText
    setHeight(newText.lines().size)

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

    textWidget.setLineVerticalIndent(textWidget.getLineAtOffset(textWidget.caretOffset), 0)
    document.removePosition(documentPosition)

    text = null
    documentPosition = null
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

  private fun setHeight(numberOfLines: Int) {
    height = (numberOfLines - 1) * textWidget.lineHeight
  }
}
