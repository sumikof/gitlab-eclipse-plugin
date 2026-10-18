package com.gitlab.eclipse.codesuggestions.handlers

import com.gitlab.eclipse.codesuggestions.languages.CodeSuggestionsLanguageService
import com.gitlab.eclipse.codesuggestions.languages.refreshCodeSuggestionsLanguageToggle
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.lsp.utils.LanguageServerLanguage.language
import com.gitlab.eclipse.lsp.utils.LanguageServerLanguage.languageId
import com.gitlab.eclipse.utils.PlatformUtils
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.resources.IFile
import org.eclipse.ui.IFileEditorInput
import org.eclipse.ui.commands.IElementUpdater
import org.eclipse.ui.menus.UIElement

class ToggleCodeSuggestionForLanguageHandler : AbstractHandler(), IElementUpdater {
  private val currentFileEditorInput
    get() = service<PlatformUtils>().getActiveTextEditor()?.editorInput as? IFileEditorInput

  override fun execute(event: ExecutionEvent) {
    val fileEditorInput = currentFileEditorInput ?: return
    val fileLanguageIdentifier = fileEditorInput.file.languageIdentifier ?: return

    service<CodeSuggestionsLanguageService>().toggleLanguage(fileLanguageIdentifier)
    service<GitLabLanguageServerConfigurationService>().sendConfiguration()
    refreshCodeSuggestionsLanguageToggle()
  }

  override fun isEnabled(): Boolean {
    return currentFileEditorInput != null
  }

  override fun updateElement(element: UIElement, filters: Map<*, *>) {
    val currentFile = currentFileEditorInput?.file
      ?: return element.displayNoActionAvailable()

    val currentLanguageIdentifier = currentFile.languageIdentifier
      ?: return element.displayNoActionAvailable()

    val currentLanguageName = currentFile.languageName
      ?: return element.displayNoActionAvailable()

    val isEnabled = service<CodeSuggestionsLanguageService>().isEnabled(currentLanguageIdentifier)
    element.setText("${if (isEnabled) "Disable" else "Enable"} Code Suggestions for $currentLanguageName")
  }

  private fun UIElement.displayNoActionAvailable() {
    setText("No code suggestions action available.")
  }

  // The same id the document is opened with in the language server, which is what its language settings match
  private val IFile.languageIdentifier: String?
    get() = name?.let { languageId }

  private val IFile.languageName: String?
    get() = language?.humanReadableName ?: fileExtension?.ifEmpty { null } ?: name
}
