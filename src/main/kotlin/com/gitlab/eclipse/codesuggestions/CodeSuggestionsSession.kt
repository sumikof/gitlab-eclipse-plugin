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
import org.eclipse.swt.events.MouseEvent
import org.eclipse.swt.events.MouseListener

@Suppress("MagicNumber", "EmptyFunctionBlock", "TooManyFunctions")
internal class CodeSuggestionsSession(
  private val textWidget: StyledText,
  private val document: IDocument,
  private val codeSuggestionsProvider: CodeSuggestionsProvider,
  private val codeSuggestionsRenderer: CodeSuggestionsRenderer,
) : IDocumentListener, KeyListener, MouseListener {
  companion object {
    private val ARROW_KEYS = listOf(SWT.ARROW_RIGHT, SWT.ARROW_LEFT, SWT.ARROW_UP, SWT.ARROW_DOWN)
  }

  private val logger by lazy { logger<CodeSuggestionsSession>() }

  override fun documentAboutToBeChanged(event: DocumentEvent) = cancelCodeSuggestion()
  override fun mouseDown(e: MouseEvent) = cancelCodeSuggestion()

  override fun documentChanged(event: DocumentEvent) {
    if (event.text.isEmpty()) {
      return
    }

    requestCodeSuggestion(event.offset + event.text.length)
  }

  override fun keyPressed(e: KeyEvent) {
    if (e.keyCode in ARROW_KEYS) {
      cancelCodeSuggestion()
    }
  }

  override fun keyReleased(e: KeyEvent) = Unit
  override fun mouseDoubleClick(e: MouseEvent) = Unit
  override fun mouseUp(e: MouseEvent) = Unit

  init {
    try {
      document.addDocumentListener(this)
      textWidget.addKeyListener(this)
      textWidget.addMouseListener(this)
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

      if (codeSuggestionsRenderer.isCodeSuggestionDisplayed()) {
        return
      }

      codeSuggestionsRenderer.display(codeSuggestionsProvider.provide(), offset)
    } catch (e: Exception) {
      logger.error("Error requesting code suggestion.", e)
    }
  }

  fun acceptCodeSuggestion() {
    val offset = codeSuggestionsRenderer.offset
    val text = codeSuggestionsRenderer.text
      ?: return

    codeSuggestionsRenderer.clear()

    document.replace(offset, 0, text)
    textWidget.caretOffset = offset + text.length
  }

  fun cancelCodeSuggestion() {
    try {
      codeSuggestionsRenderer.clear()
    } catch (e: Exception) {
      logger.error("Error canceling code suggestion.", e)
    }
  }

  fun rejectCodeSuggestion() {
    try {
      codeSuggestionsRenderer.reject()
    } catch (e: Exception) {
      logger.error("Error rejecting code suggestion.", e)
    }
  }

  fun isCodeSuggestionDisplayed() = codeSuggestionsRenderer.isCodeSuggestionDisplayed()

  fun dispose() {
    try {
      codeSuggestionsRenderer.dispose()
      document.removeDocumentListener(this)
      textWidget.removeKeyListener(this)
      textWidget.removeMouseListener(this)
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
