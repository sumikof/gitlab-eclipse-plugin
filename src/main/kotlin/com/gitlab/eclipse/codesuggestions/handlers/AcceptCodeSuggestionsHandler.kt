package com.gitlab.eclipse.codesuggestions.handlers

import com.gitlab.eclipse.BuildConfig
import com.gitlab.eclipse.codesuggestions.CodeSuggestionsManager
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent

class AcceptCodeSuggestionsHandler : AbstractHandler() {
  private val logger by lazy { logger<AcceptCodeSuggestionsHandler>() }
  private val codeSuggestionsManager by lazyService<CodeSuggestionsManager>()

  override fun execute(event: ExecutionEvent) {
    try {
      codeSuggestionsManager.acceptCodeSuggestion()
    } catch (e: Exception) {
      logger.error("Failed to accept a code suggestion.", e)
    }
  }

  override fun isEnabled(): Boolean {
    return BuildConfig.CODE_SUGGESTIONS_ENABLED && codeSuggestionsManager.isCodeSuggestionDisplayed()
  }
}
