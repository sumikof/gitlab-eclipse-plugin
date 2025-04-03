package com.gitlab.eclipse.codesuggestions.handlers

import com.gitlab.eclipse.codesuggestions.CycleDirection

class CycleToPreviousCodeSuggestionHandler : CycleCodeSuggestionHandler() {
  override val cycleDirection = CycleDirection.PREVIOUS
}
