package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.utils.logger
import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.Position
import org.eclipse.swt.custom.StyleRange
import org.eclipse.swt.custom.StyledText
import org.eclipse.swt.custom.StyledTextLineSpacingProvider
import org.eclipse.swt.events.PaintEvent
import org.eclipse.swt.events.PaintListener
import org.eclipse.swt.graphics.*

@Suppress("TooManyFunctions")
class CodeSuggestionsRenderer(
  private val document: IDocument,
  private val textWidget: StyledText,
) : PaintListener {
  private val logger by lazy { logger<CodeSuggestionsRenderer>() }

  companion object {
    private val GHOST_COLOR = Color(128, 128, 128)
  }

  private val lineSpacingProvider = LineSpacingProvider(0, -1)

  init {
    textWidget.addPaintListener(this)
    textWidget.setLineSpacingProvider(lineSpacingProvider)
  }

  var documentPosition: Position? = null
    private set

  var text: String? = null
    private set

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
        // Updating line spacing before a document change is rendered can cause an out-of-bounds exception.
        if (lineSpacingProvider.line == -1 && documentPosition != null) {
          updateLineSpacingProvider()
        }

        renderBlock(lines.drop(1), position, paintEvent.gc)
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

  private fun renderBlock(lines: List<String>, position: Point, gc: GC) {
    lines.forEachIndexed { index, line ->
      val layout = TextLayout(textWidget.display).apply {
        text = line
        font = textWidget.font
        tabs = textWidget.tabStops
      }

      val lineY = position.y + (index + 1) * textWidget.lineHeight

      gc.fillRectangle(
        0,
        lineY,
        textWidget.clientArea.width,
        textWidget.lineHeight
      )

      gc.foreground = GHOST_COLOR
      layout.draw(gc, textWidget.leftMargin, lineY)
      layout.dispose()
    }
  }

  fun display(
    text: String,
    offset: Int,
    forceRedraw: Boolean = true
  ) {
    document.removePosition(documentPosition)

    this.text = text
    this.documentPosition = Position(offset)

    document.addPosition(documentPosition)

    textWidget.redrawNow(forceRedraw)
  }

  fun update(newText: String) {
    this.text = newText
    updateLineSpacingProvider()
    textWidget.redrawNow()
  }

  fun reject() {
    clear()
    textWidget.redrawNow()
  }

  fun dispose() {
    textWidget.removePaintListener(this)

    clear()

    textWidget.setLineSpacingProvider(null)
    textWidget.redrawNow()
  }

  fun clear() {
    suggestionCharacterStyle?.let { style ->
      style.metrics = GlyphMetrics(0, 0, 0)
      textWidget.setStyleRange(style)
    }
    suggestionCharacterStyle = null

    document.removePosition(documentPosition)
    documentPosition = null

    text = null
    updateLineSpacingProvider()
  }

  fun isCodeSuggestionDisplayed(): Boolean {
    return text != null
  }

  private fun isEndOfLine(offset: Int): Boolean {
    val line = textWidget.getLineAtOffset(offset)
    val lineEndOffset = textWidget.getOffsetAtLine(line) + textWidget.getLine(line).length

    return offset == lineEndOffset
  }

  /**
   * The force flag should be used if textWidget updates are not happening within the same thread.
   * Default is true to ensure UI updates are visible immediately.
   */
  private fun StyledText.redrawNow(force: Boolean = true) {
    if (force) {
      redraw()
      update()
    } else {
      setRedraw(true)
    }
  }

  private fun updateLineSpacingProvider() {
    val currentText = text
    val currentOffset = documentPosition

    if (currentText == null || currentOffset == null) {
      lineSpacingProvider.line = -1
      return
    }

    lineSpacingProvider.line = textWidget.getLineAtOffset(currentOffset.offset)
    lineSpacingProvider.height = (currentText.lines().size - 1) * textWidget.lineHeight
  }

  private class LineSpacingProvider(
    var height: Int,
    var line: Int
  ) : StyledTextLineSpacingProvider {
    override fun getLineSpacing(lineIndex: Int): Int? {
      return when {
        lineIndex == line -> height
        else -> null
      }
    }
  }
}
