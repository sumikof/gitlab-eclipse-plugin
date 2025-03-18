package com.gitlab.eclipse.codesuggestions.listeners

import com.gitlab.eclipse.codesuggestions.CodeSuggestionsSession
import com.gitlab.eclipse.utils.logger
import org.eclipse.swt.custom.StyledText
import org.eclipse.swt.events.MouseEvent
import org.eclipse.swt.events.MouseListener

class CodeSuggestionsMouseListener(
  private val textWidget: StyledText,
  private val session: CodeSuggestionsSession
) : MouseListener {
  private val logger by lazy { logger<CodeSuggestionsMouseListener>() }

  init {
    textWidget.addMouseListener(this)
  }

  override fun mouseDown(e: MouseEvent) = session.cancelCodeSuggestion()
  override fun mouseDoubleClick(e: MouseEvent) = Unit
  override fun mouseUp(e: MouseEvent) = Unit

  fun dispose() {
    try {
      textWidget.removeMouseListener(this)
    } catch (e: Exception) {
      logger.error("Error disposing code suggestions mouse listener.", e)
    }
  }
}
