package com.gitlab.eclipse.codesuggestions.listeners

import com.gitlab.eclipse.codesuggestions.CodeSuggestionsSession
import com.gitlab.eclipse.utils.logger
import org.eclipse.swt.custom.CaretEvent
import org.eclipse.swt.custom.CaretListener
import org.eclipse.swt.custom.StyledText

class CodeSuggestionsCaretListener(
  private val textWidget: StyledText,
  private val codeSuggestionsSession: CodeSuggestionsSession
) : CaretListener {
  private val logger by lazy { logger<CodeSuggestionsCaretListener>() }

  private var lastKnownOffset = 0
  private var caretMovementReason: CaretMovementReason = CaretMovementReason.UNKNOWN

  init {
    textWidget.addCaretListener(this)
  }

  override fun caretMoved(event: CaretEvent) {
    /*
     * Eclipse's CaretListener can randomly send an event with the offset 0 when the caret has not moved.
     * This prevents those events from being processed.
     * Any real caret movement to this offset will be caught in key or mouse listener.
     */
    if (event.caretOffset == 0) {
      return
    }

    if (caretMovementReason == CaretMovementReason.UNKNOWN && lastKnownOffset != event.caretOffset) {
      codeSuggestionsSession.cancelCodeSuggestion()
    }

    lastKnownOffset = event.caretOffset
    caretMovementReason = CaretMovementReason.UNKNOWN
  }

  fun setCaretMovementReason(reason: CaretMovementReason) {
    caretMovementReason = reason
  }

  fun dispose() {
    try {
      textWidget.removeCaretListener(this)
    } catch (e: Throwable) {
      logger.error("Error disposing code suggestions caret listener.", e)
    }
  }
}

enum class CaretMovementReason {
  SUGGESTION_ACCEPTED,
  USER_TYPED,
  UNKNOWN
}
