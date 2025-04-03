package com.gitlab.eclipse.codesuggestions.handlers

import com.gitlab.eclipse.codesuggestions.CycleDirection

class CycleToNextCodeSuggestionHandler : CycleCodeSuggestionHandler() {
  override val cycleDirection = CycleDirection.NEXT
}
