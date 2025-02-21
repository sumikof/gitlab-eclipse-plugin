package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.CodeSuggestionsApiStatusService
import com.gitlab.eclipse.utils.logger
import org.eclipse.swt.custom.StyledText

@Suppress("MagicNumber")
internal class CodeSuggestionsSession(
  private val textWidget: StyledText,
  private val codeSuggestionsProvider: CodeSuggestionsProvider,
) {
  private val logger by lazy { logger<CodeSuggestionsSession>() }

  private var codeSuggestionsRenderer: CodeSuggestionsRenderer? = null

  fun start() {
    try {
      // TODO: Implement the session creation
    } catch (e: Exception) {
      logger.error("Error starting code suggestion session.", e)
    }
  }

  fun requestCodeSuggestion() {
    try {
      if (!isEnabled()) {
        return
      }

      clear()

      codeSuggestionsRenderer = CodeSuggestionsRenderer(
        textWidget,
        textWidget.caretOffset,
        codeSuggestionsProvider.provide()
      )
    } catch (e: Exception) {
      logger.error("Error requesting code suggestion.", e)
    }
  }

  fun cancelCodeSuggestion() {
    try {
      clear()
    } catch (e: Exception) {
      logger.error("Error canceling code suggestion session.", e)
    }
  }

  fun dispose() {
    try {
      clear()
    } catch (e: Exception) {
      logger.error("Error disposing code suggestion session.", e)
    }
  }

  private fun clear() {
    codeSuggestionsRenderer?.dispose()
    codeSuggestionsRenderer = null
  }

  private fun isEnabled(): Boolean {
    val codeSuggestionsIsEnabled = service<CodeSuggestionsStateService>().isEnabled
    val codeSuggestionsApiInError =
      service<CodeSuggestionsApiStatusService>().apiStatus.value == CodeSuggestionsApiStatusService.ApiStatus.Error

    return codeSuggestionsIsEnabled && !codeSuggestionsApiInError
  }
}
