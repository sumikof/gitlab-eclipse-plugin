package com.gitlab.eclipse.codesuggestions.handlers

import com.gitlab.eclipse.codesuggestions.CodeSuggestionsManager
import com.gitlab.eclipse.codesuggestions.CycleDirection
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent

abstract class CycleCodeSuggestionHandler : AbstractHandler() {
  private val logger by lazy { logger<CycleCodeSuggestionHandler>() }
  private val codeSuggestionsManager by lazyService<CodeSuggestionsManager>()
  private val platformUtils by lazyService<PlatformUtils>()

  override fun execute(event: ExecutionEvent) {
    try {
      val textEditor = platformUtils.getActiveTextEditor() ?: return
      val session = codeSuggestionsManager.getOrCreateSession(textEditor)

      session.cycleCodeSuggestion(cycleDirection)
    } catch (e: Exception) {
      logger.error("Failed to cycle through code suggestions.", e)
    }
  }

  override fun isEnabled(): Boolean {
    val textEditor = platformUtils.getActiveTextEditor() ?: return false

    return codeSuggestionsManager.isSuggestionDisplayed(textEditor)
  }

  protected abstract val cycleDirection: CycleDirection
}
