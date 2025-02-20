package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.CodeSuggestionsApiStatusService
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.*
import org.eclipse.swt.custom.StyledText
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Suppress("MagicNumber")
internal class CodeSuggestionsSession(
  private val textWidget: StyledText,
  private val coroutineScope: CoroutineScope,
) {
  private val logger = logger<CodeSuggestionsSession>()

  private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

  private var codeSuggestionsRenderer: CodeSuggestionsRenderer? = null
  private var job: Job? = null

  fun start(): Boolean {
    try {
      // This demos the suggestion being displayed and updated.
      codeSuggestionsRenderer = CodeSuggestionsRenderer(
        textWidget,
        textWidget.caretOffset,
        LocalTime.now().format(timeFormatter)
      )

      job = coroutineScope.launch {
        while (isActive) {
          if (!textWidget.isDisposed) {
            textWidget.display.asyncExec {
              codeSuggestionsRenderer?.update(LocalTime.now().format(timeFormatter))
            }
          }

          delay(1000) // 1 second
        }
      }
      return true
    } catch (e: Exception) {
      logger.error("Error starting code suggestion session", e)
      return false
    }
  }

  fun dispose() {
    try {
      codeSuggestionsRenderer?.dispose()
      codeSuggestionsRenderer = null

      job?.cancel()
      job = null
    } catch (e: Exception) {
      logger.error("Error disposing code suggestion session", e)
    }
  }

  private fun isEnabled(): Boolean {
    val codeSuggestionsIsEnabled = service<CodeSuggestionsStateService>().isEnabled
    val codeSuggestionsApiInError =
      service<CodeSuggestionsApiStatusService>().apiStatus.value == CodeSuggestionsApiStatusService.ApiStatus.Error

    return codeSuggestionsIsEnabled && !codeSuggestionsApiInError
  }
}
