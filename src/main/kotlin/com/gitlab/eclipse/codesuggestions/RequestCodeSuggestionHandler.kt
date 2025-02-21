package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.BuildConfig
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent

class RequestCodeSuggestionHandler : AbstractHandler() {
  private val logger = logger<RequestCodeSuggestionHandler>()
  private val codeSuggestionsManager by lazyService<CodeSuggestionsManager>()

  override fun execute(event: ExecutionEvent?) {
    try {
      codeSuggestionsManager.requestCodeSuggestion()
    } catch (e: Exception) {
      logger.error("Failed to request a code suggestion.", e)
    }
  }

  override fun isEnabled() = BuildConfig.CODE_SUGGESTIONS_ENABLED
}
