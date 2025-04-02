package com.gitlab.eclipse.codesuggestions.handlers

import com.gitlab.eclipse.codesuggestions.CodeSuggestionsSession

class CycleToPreviousCodeSuggestionHandler : CycleCodeSuggestionHandler() {
  override fun cycleSuggestion(session: CodeSuggestionsSession) {
    session.cycleToPreviousSuggestion()
  }
}
