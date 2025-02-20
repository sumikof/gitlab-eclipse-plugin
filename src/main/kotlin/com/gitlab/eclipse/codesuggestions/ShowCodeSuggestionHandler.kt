package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.BuildConfig
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent

class ShowCodeSuggestionHandler : AbstractHandler() {
  private val logger = logger<ShowCodeSuggestionHandler>()
  private val codeSuggestionsManager by lazyService<CodeSuggestionsManager>()

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
