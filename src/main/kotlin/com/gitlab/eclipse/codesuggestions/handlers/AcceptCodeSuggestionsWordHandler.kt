package com.gitlab.eclipse.codesuggestions.handlers

import com.gitlab.eclipse.codesuggestions.CodeSuggestionsManager
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import kotlin.getValue

class AcceptCodeSuggestionsWordHandler : AbstractHandler() {
  private val logger by lazy { logger<AcceptCodeSuggestionsHandler>() }
  private val codeSuggestionsManager by lazyService<CodeSuggestionsManager>()
  private val platformUtils by lazyService<PlatformUtils>()

  override fun execute(event: ExecutionEvent) {
    try {
      val textEditor = platformUtils.getActiveTextEditor()
        ?: return

      codeSuggestionsManager.getOrCreateSession(textEditor).acceptCodeSuggestionWord()
    } catch (e: Exception) {
      logger.error("Failed to accept code suggestion word.", e)
    }
  }

  override fun isEnabled(): Boolean {
    val textEditor = platformUtils.getActiveTextEditor()
      ?: return false

    return codeSuggestionsManager.isSuggestionDisplayed(textEditor)
  }
}
