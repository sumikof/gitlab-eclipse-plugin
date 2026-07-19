package com.gitlab.eclipse.codesuggestions.handlers

import com.gitlab.eclipse.codesuggestions.dismissActiveCodeSuggestion
import com.gitlab.eclipse.codesuggestions.refreshCodeSuggestionsToggle
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.preferences.PreferenceConstants
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.commands.IElementUpdater
import org.eclipse.ui.menus.UIElement
import org.eclipse.ui.preferences.ScopedPreferenceStore

class ToggleCodeSuggestionsHandler : AbstractHandler(), IElementUpdater {
  override fun execute(event: ExecutionEvent) {
    val preferenceStore = service<ScopedPreferenceStore>()
    val newValue = !preferenceStore.getBoolean(PreferenceConstants.CODE_SUGGESTIONS_ENABLED)
    preferenceStore.putValue(PreferenceConstants.CODE_SUGGESTIONS_ENABLED, newValue.toString())

    service<GitLabLanguageServerConfigurationService>().sendConfiguration()

    if (!newValue) {
      dismissActiveCodeSuggestion()
    }

    refreshCodeSuggestionsToggle()
  }

  override fun updateElement(element: UIElement, filters: Map<*, *>) {
    val isEnabled = service<ScopedPreferenceStore>().getBoolean(PreferenceConstants.CODE_SUGGESTIONS_ENABLED)
    element.setText(if (isEnabled) "Disable Code Suggestions" else "Enable Code Suggestions")
  }
}
