package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.*
import org.eclipse.jface.text.ITextViewer
import org.eclipse.swt.custom.StyledText
import org.eclipse.swt.events.PaintListener
import org.eclipse.swt.graphics.Color
import org.eclipse.swt.graphics.Font
import org.eclipse.swt.graphics.RGB
import org.eclipse.ui.texteditor.ITextEditor
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Suppress("MagicNumber")
internal class CodeSuggestionsSession(
  private val coroutineScope: CoroutineScope,
) {
  private val logger = logger<CodeSuggestionsSession>()

  private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

  private lateinit var widget: StyledText

  private var ghostTextFont: Font? = null
  private var renderer: PaintListener? = null
  private var job: Job? = null

  // TODO: Replace during LS integration
  private var currentSuggestion: String = ""

  fun start(editor: ITextEditor): Boolean {
    widget = editor.getAdapter(ITextViewer::class.java)
      ?.textWidget ?: run {
      logger.error("Failed to get active text widget")
      return false
    }

    // Construct the ghost text font once and reuse it throughout the session
    ghostTextFont = Font(widget.display, widget.font.fontData)

    renderer = PaintListener { paintEvent ->
      try {
        if (currentSuggestion.isEmpty()) return@PaintListener

        // Get current caret position
        val caretOffset = widget.caretOffset
        val caretPos = widget.getLocationAtOffset(caretOffset)

        // Setup ghost text style
        paintEvent.gc.font = ghostTextFont

        val ghostColor = Color(paintEvent.display, RGB(128, 128, 128))
        paintEvent.gc.foreground = ghostColor

        // Draw the suggestion text
        paintEvent.gc.drawString(currentSuggestion, caretPos.x, caretPos.y, true)

        // Cleanup
        ghostColor.dispose()
      } catch (e: Exception) {
        logger.error("Error rendering code suggestion", e)
      }
    }

    // This demos the suggestion being updated.
    job = coroutineScope.launch {
      while (isActive) {
        val newSuggestion = LocalTime.now().format(timeFormatter)

        if (!widget.isDisposed) {
          widget.display.asyncExec {
            if (!widget.isDisposed) {
              currentSuggestion = newSuggestion
              widget.redraw()
            }
          }
        }

        delay(1000) // 1 second
      }
    }

    widget.addPaintListener(renderer)
    return true
  }

  fun dispose() {
    try {
      if (::widget.isInitialized && !widget.isDisposed) {
        widget.removePaintListener(renderer)
      }

      ghostTextFont?.dispose()
      ghostTextFont = null

      renderer = null

      job?.cancel()
      job = null
    } catch (e: Exception) {
      logger.error("Error disposing code suggestion session", e)
    }
  }
}
