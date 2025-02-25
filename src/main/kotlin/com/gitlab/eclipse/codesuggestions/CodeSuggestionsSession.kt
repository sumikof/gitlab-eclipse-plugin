package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.CodeSuggestionsApiStatusService
import com.gitlab.eclipse.utils.logger
import org.eclipse.jface.text.DocumentEvent
import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.IDocumentListener
import org.eclipse.swt.SWT
import org.eclipse.swt.custom.StyledText
import org.eclipse.swt.events.KeyEvent
import org.eclipse.swt.events.KeyListener

@Suppress("MagicNumber", "EmptyFunctionBlock")
internal class CodeSuggestionsSession(
  private val textWidget: StyledText,
  private val document: IDocument,
  private val codeSuggestionsProvider: CodeSuggestionsProvider,
  private val codeSuggestionsRenderer: CodeSuggestionsRenderer,
) : IDocumentListener, KeyListener {
  private val logger by lazy { logger<CodeSuggestionsSession>() }

  private var isFilteredKeyPress = false

  override fun documentAboutToBeChanged(event: DocumentEvent) {
    codeSuggestionsRenderer.clear()
  }

  override fun documentChanged(event: DocumentEvent) {
    if (isFilteredKeyPress) {
      return
    }

    requestCodeSuggestion(event.offset + event.text.length)
  }

  override fun keyReleased(e: KeyEvent) {
    isFilteredKeyPress = false
  }

  override fun keyPressed(e: KeyEvent) {
    if (e.character in listOf(SWT.TAB, SWT.BS)) {
      isFilteredKeyPress = true
      cancelCodeSuggestion()
    }
  }

  init {
    try {
      document.addDocumentListener(this)
      textWidget.addKeyListener(this)
    } catch (e: Exception) {
      logger.error("Error starting code suggestion session.", e)
    }
  }

  fun requestCodeSuggestion(newOffset: Int? = null) {
    val offset = newOffset ?: textWidget.caretOffset

    try {
      if (!isEnabled()) {
        return
      }

      if (isFilteredKeyPress) {
        return
      }

      codeSuggestionsRenderer.display(codeSuggestionsProvider.provide(), offset)
    } catch (e: Exception) {
      logger.error("Error requesting code suggestion.", e)
    }
  }

  fun cancelCodeSuggestion() {
    try {
      codeSuggestionsRenderer.clear()
    } catch (e: Exception) {
      logger.error("Error canceling code suggestion session.", e)
    }
  }

  fun dispose() {
    try {
      codeSuggestionsRenderer.dispose()
      document.removeDocumentListener(this)
      textWidget.removeKeyListener(this)
    } catch (e: Exception) {
      logger.error("Error disposing code suggestion session.", e)
    }
  }

  private fun isEnabled(): Boolean {
    val codeSuggestionsIsEnabled = service<CodeSuggestionsStateService>().isEnabled
    val codeSuggestionsApiInError =
      service<CodeSuggestionsApiStatusService>().apiStatus.value == CodeSuggestionsApiStatusService.ApiStatus.Error

    return codeSuggestionsIsEnabled && !codeSuggestionsApiInError
  }
}
