package com.gitlab.eclipse.codesuggestions.languages

import com.gitlab.eclipse.preferences.PreferenceConstants
import org.eclipse.ui.preferences.ScopedPreferenceStore

class CodeSuggestionsLanguageService(
  private val preferenceStore: ScopedPreferenceStore,
) {
  fun getAdditionalLanguages(): List<String> {
    return preferenceStore
      .getString(PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES)
      .extractLanguages()
  }

  fun getDisabledLanguages(): List<String> {
    return preferenceStore
      .getString(PreferenceConstants.CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES)
      .extractLanguages()
  }

  private fun String.extractLanguages(): List<String> {
    if (this.isBlank()) {
      return emptyList()
    }

    return this
      .split(",")
      .map { it.trim() }
      .filter { it.isNotEmpty() }
  }
}
