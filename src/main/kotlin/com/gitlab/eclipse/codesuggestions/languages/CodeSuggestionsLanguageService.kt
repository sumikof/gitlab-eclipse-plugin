package com.gitlab.eclipse.codesuggestions.languages

import com.gitlab.eclipse.lsp.utils.LanguageServerLanguage
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

  fun isEnabled(languageId: String): Boolean {
    val isSupportedLanguage = LanguageServerLanguage.Language.entries.any {
      it.id == languageId
    }

    if (isSupportedLanguage) {
      return !getDisabledLanguages().contains(languageId)
    }

    return getAdditionalLanguages().any { it.equals(languageId, ignoreCase = true) }
  }

  fun toggleLanguage(languageIdentifier: String) {
    val supportedLanguage = LanguageServerLanguage.Language.entries.firstOrNull {
      it.extensions.contains(languageIdentifier)
    }

    if (supportedLanguage != null) {
      toggleSupportedLanguage(supportedLanguage)
    } else {
      toggleAdditionalLanguage(languageIdentifier)
    }
  }

  private fun toggleSupportedLanguage(supportedLanguage: LanguageServerLanguage.Language) {
    val disabledLanguages = getDisabledLanguages().toMutableList()
    toggleItemInList(disabledLanguages, supportedLanguage.id)

    preferenceStore.putValue(
      PreferenceConstants.CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES,
      disabledLanguages.joinToString(",") { it.lowercase() }
    )
  }

  private fun toggleAdditionalLanguage(fileExtension: String) {
    val additionalLanguages = getAdditionalLanguages().toMutableList()
    toggleItemInList(additionalLanguages, fileExtension)

    preferenceStore.putValue(
      PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES,
      additionalLanguages.joinToString(",") { it.lowercase() }
    )
  }

  private fun toggleItemInList(list: MutableList<String>, item: String) {
    val itemWasRemoved = list.removeIf { it.equals(item, ignoreCase = true) }
    if (!itemWasRemoved) {
      list.add(item)
    }
  }

  private fun String.extractLanguages(): List<String> {
    if (this.isBlank()) {
      return emptyList()
    }

    return this
      .split(",")
      .map { it.trim().lowercase() }
      .filter { it.isNotEmpty() }
  }
}
