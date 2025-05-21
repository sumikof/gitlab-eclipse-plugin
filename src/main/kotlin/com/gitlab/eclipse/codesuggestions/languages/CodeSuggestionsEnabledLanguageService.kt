package com.gitlab.eclipse.codesuggestions.languages

import com.gitlab.eclipse.preferences.PreferenceConstants
import org.eclipse.ui.preferences.ScopedPreferenceStore

class CodeSuggestionsEnabledLanguageService(
  private val preferenceStore: ScopedPreferenceStore,
) {
  fun getAdditionalLanguages(): List<String> {
    val additionalLanguages = preferenceStore.getString(PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES)

    if (additionalLanguages.isBlank()) {
      return emptyList()
    }

    return additionalLanguages
      .split(",")
      .map { it.trim() }
      .filter { it.isNotEmpty() }
  }
}
