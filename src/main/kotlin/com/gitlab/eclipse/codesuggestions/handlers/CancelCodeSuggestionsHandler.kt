package com.gitlab.eclipse.codesuggestions.handlers

import com.gitlab.eclipse.BuildConfig
import com.gitlab.eclipse.codesuggestions.CodeSuggestionsManager
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent

class CancelCodeSuggestionsHandler : AbstractHandler() {
  private val logger by lazy { logger<CancelCodeSuggestionsHandler>() }
  private val codeSuggestionsManager by lazyService<CodeSuggestionsManager>()

  override fun execute(event: ExecutionEvent) {
    try {
      codeSuggestionsManager.cancelCodeSuggestion()
    } catch (e: Exception) {
      logger.error("Failed to cancel code suggestions.", e)
    }
  }

  override fun isEnabled() = BuildConfig.CODE_SUGGESTIONS_ENABLED
}
