package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.BuildConfig
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ShowCodeSuggestionHandler : AbstractHandler(), KoinComponent {
  private val logger = logger<ShowCodeSuggestionHandler>()
  private val codeSuggestionsManager: CodeSuggestionsManager by inject()

  override fun execute(event: ExecutionEvent?) {
    try {
      if (!BuildConfig.CODE_SUGGESTIONS_ENABLED) {
        logger.info("Code Suggestions disabled by build flag.")
        return
      }

      codeSuggestionsManager.startSession()
    } catch (e: Exception) {
      logger.error("ShowCodeSuggestionHandler execution failed", e)
    }
  }
}
