package com.gitlab.eclipse.codesuggestions.handlers

import com.gitlab.eclipse.codesuggestions.CodeSuggestionsSession

class CycleToNextCodeSuggestionHandler : CycleCodeSuggestionHandler() {
  override fun cycleSuggestion(session: CodeSuggestionsSession) {
    session.cycleToNextSuggestion()
  }
}
