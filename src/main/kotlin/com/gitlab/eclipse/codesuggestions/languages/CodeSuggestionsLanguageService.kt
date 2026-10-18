package com.gitlab.eclipse.codesuggestions.languages

import com.gitlab.eclipse.lsp.utils.LanguageServerLanguage
import com.gitlab.eclipse.lsp.utils.LanguageServerLanguage.normalizeIdentifier
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
    val normalizedLanguageId = normalizeIdentifier(languageId)

    if (supportedLanguageOf(normalizedLanguageId) != null) {
      return !getDisabledLanguages().contains(normalizedLanguageId)
    }

    return getAdditionalLanguages().contains(normalizedLanguageId)
  }

  /** Takes the language id of the document, the one sent to the language server (see `IFile.languageId`). */
  fun toggleLanguage(languageId: String) {
    val normalizedLanguageId = normalizeIdentifier(languageId)
    if (normalizedLanguageId.isEmpty()) {
      return
    }

    val supportedLanguage = supportedLanguageOf(normalizedLanguageId)

    if (supportedLanguage != null) {
      toggleSupportedLanguage(supportedLanguage)
    } else {
      toggleAdditionalLanguage(normalizedLanguageId)
    }
  }

  private fun supportedLanguageOf(languageId: String) =
    LanguageServerLanguage.Language.entries.firstOrNull { it.id == languageId }

  private fun toggleSupportedLanguage(supportedLanguage: LanguageServerLanguage.Language) {
    val disabledLanguages = getDisabledLanguages().toMutableList()
    toggleItemInList(disabledLanguages, supportedLanguage.id)

    preferenceStore.putValue(
      PreferenceConstants.CODE_SUGGESTIONS_DISABLED_SUPPORTED_LANGUAGES,
      disabledLanguages.joinToString(",") { it.lowercase() }
    )

    removeFromAdditionalLanguages(supportedLanguage.id)
  }

  // The toggle used to file supported languages whose id is not one of their extensions (python, kotlin, ...)
  // under additional languages. Harmless to the language server, but it shows up in the preferences.
  private fun removeFromAdditionalLanguages(languageId: String) {
    val additionalLanguages = getAdditionalLanguages()
    if (languageId !in additionalLanguages) {
      return
    }

    preferenceStore.putValue(
      PreferenceConstants.CODE_SUGGESTIONS_ADDITIONAL_LANGUAGES,
      (additionalLanguages - languageId).joinToString(",")
    )
  }

  private fun toggleAdditionalLanguage(languageId: String) {
    val additionalLanguages = getAdditionalLanguages().toMutableList()
    toggleItemInList(additionalLanguages, languageId)

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
      .map(::normalizeIdentifier)
      .filter { it.isNotEmpty() }
      .distinct()
  }
}
