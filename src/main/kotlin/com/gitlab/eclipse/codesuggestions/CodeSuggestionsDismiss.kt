package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.utils.PlatformUtils

/**
 * Cancels any pending request and clears any displayed suggestion in the active editor.
 * Used when Code Suggestions is disabled (via the toggle command or the preference page).
 */
fun dismissActiveCodeSuggestion() {
  val editor = service<PlatformUtils>().getActiveTextEditor() ?: return
  service<CodeSuggestionsManager>().getOrCreateSession(editor).cancelCodeSuggestion()
}
