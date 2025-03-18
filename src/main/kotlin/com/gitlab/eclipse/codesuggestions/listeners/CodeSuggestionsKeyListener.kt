package com.gitlab.eclipse.codesuggestions.listeners

import com.gitlab.eclipse.codesuggestions.CodeSuggestionsSession
import com.gitlab.eclipse.utils.logger
import org.eclipse.swt.SWT
import org.eclipse.swt.custom.StyledText
import org.eclipse.swt.events.KeyEvent
import org.eclipse.swt.events.KeyListener

class CodeSuggestionsKeyListener(
  private val textWidget: StyledText,
  private val session: CodeSuggestionsSession,
) : KeyListener {
  companion object {
    private val ARROW_KEYS = listOf(SWT.ARROW_RIGHT, SWT.ARROW_LEFT, SWT.ARROW_UP, SWT.ARROW_DOWN)
  }

  private val logger by lazy { logger<CodeSuggestionsKeyListener>() }

  init {
    textWidget.addKeyListener(this)
  }

  override fun keyPressed(e: KeyEvent) {
    if (e.keyCode in ARROW_KEYS) {
      session.cancelCodeSuggestion()
    }
  }

  override fun keyReleased(e: KeyEvent) = Unit

  fun dispose() {
    try {
      textWidget.removeKeyListener(this)
    } catch (e: Exception) {
      logger.error("Error disposing code suggestions key listener.", e)
    }
  }
}
