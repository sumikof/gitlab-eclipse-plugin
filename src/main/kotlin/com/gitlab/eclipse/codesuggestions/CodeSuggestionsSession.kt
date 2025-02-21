package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.CodeSuggestionsApiStatusService
import com.gitlab.eclipse.utils.logger
import org.eclipse.swt.custom.StyledText
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Suppress("MagicNumber")
internal class CodeSuggestionsSession(private val textWidget: StyledText) {
  private val logger = logger<CodeSuggestionsSession>()

  private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
  private var codeSuggestionsRenderer: CodeSuggestionsRenderer? = null

  fun start() {
    try {
      // This demos the suggestion being displayed.
      codeSuggestionsRenderer = CodeSuggestionsRenderer(
        textWidget,
        textWidget.caretOffset,
        "${LocalTime.now().format(timeFormatter)}\nHello"
      )
    } catch (e: Exception) {
      logger.error("Error starting code suggestion session.", e)
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
