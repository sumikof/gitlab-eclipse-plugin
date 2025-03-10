package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.codesuggestions.annotation.CodeSuggestionAnnotationType
import com.gitlab.eclipse.codesuggestions.annotation.CodeSuggestionsSessionAnnotationManager
import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.CodeSuggestionsApiStatusService
import com.gitlab.eclipse.telemetry.TelemetryService
import com.gitlab.eclipse.telemetry.params.TelemetryAction
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.utils.uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.eclipse.jface.text.DocumentEvent
import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.IDocumentListener
import org.eclipse.swt.SWT
import org.eclipse.swt.custom.StyledText
import org.eclipse.swt.events.KeyEvent
import org.eclipse.swt.events.KeyListener
import org.eclipse.swt.events.MouseEvent
import org.eclipse.swt.events.MouseListener
import org.eclipse.text.undo.DocumentUndoEvent
import org.eclipse.text.undo.DocumentUndoManagerRegistry
import org.eclipse.text.undo.IDocumentUndoListener
import kotlin.time.Duration.Companion.milliseconds

@Suppress("MagicNumber", "EmptyFunctionBlock", "TooManyFunctions")
internal class CodeSuggestionsSession(
  private val textWidget: StyledText,
  private val document: IDocument,
  private val codeSuggestionsProvider: CodeSuggestionsProvider,
  private val codeSuggestionsRenderer: CodeSuggestionsRenderer,
  private val annotationManager: CodeSuggestionsSessionAnnotationManager,
  private val telemetryService: TelemetryService,
  private val coroutineScope: CoroutineScope,
) : IDocumentListener, KeyListener, MouseListener, IDocumentUndoListener {
  private val logger by lazy { logger<CodeSuggestionsSession>() }

  private var job: Job? = null

  private var skipNextSuggestion: Boolean = false
  private var codeSuggestion: CodeSuggestion? = null

  override fun documentAboutToBeChanged(event: DocumentEvent) = Unit
  override fun mouseDown(e: MouseEvent) = cancelCodeSuggestion()

  override fun documentChanged(event: DocumentEvent) {
    if (isCodeSuggestionDisplayed()) {
      cancelCodeSuggestion()
    }

    if (!skipNextSuggestion && event.text.isNotEmpty()) {
      requestCodeSuggestion(event.offset + event.text.length)
    }

    skipNextSuggestion = false
  }

  override fun keyPressed(e: KeyEvent) {
    if (e.keyCode in ARROW_KEYS) {
      cancelCodeSuggestion()
    }
  }

  override fun keyReleased(e: KeyEvent) = Unit
  override fun mouseDoubleClick(e: MouseEvent) = Unit
  override fun mouseUp(e: MouseEvent) = Unit

  override fun documentUndoNotification(event: DocumentUndoEvent) {
    skipNextSuggestion = true
  }

  init {
    try {
      DocumentUndoManagerRegistry.getDocumentUndoManager(document).addDocumentUndoListener(this)

      document.addDocumentListener(this)
      textWidget.addKeyListener(this)
      textWidget.addMouseListener(this)
    } catch (e: Exception) {
      logger.error("Error starting code suggestion session.", e)
    }
  }

  fun requestCodeSuggestion(requestOffset: Int? = null) {
    try {
      if (!isEnabled()) return

      job?.cancel()
      job = coroutineScope.launch {
        delay(KEY_PRESS_DEBOUNCE)

        val offset = requestOffset ?: textWidget.caretOffset
        val line = document.getLineOfOffset(offset)
        val column = offset - document.getLineOffset(line)

        annotationManager.display(CodeSuggestionAnnotationType.LOADING, offset)

        codeSuggestion = codeSuggestionsProvider.provide(
          fileUri = document.uri,
          cursorLine = line,
          cursorColumn = column
        )

        if (codeSuggestion == null) {
          annotationManager.hide()
          return@launch
        }

        codeSuggestion?.let {
          currentDisplay.syncExec { codeSuggestionsRenderer.display(it.text, offset) }

          telemetryService.send(it, TelemetryAction.SUGGESTION_SHOWN)
          annotationManager.display(CodeSuggestionAnnotationType.READY, offset)
        }
      }
    } catch (e: Exception) {
      logger.error("Error requesting code suggestion.", e)
    }
  }

  fun acceptCodeSuggestion() {
    val offset = codeSuggestionsRenderer.offset
    val text = codeSuggestionsRenderer.text
      ?: return

    codeSuggestionsRenderer.clear()
    annotationManager.hide()

    document.replace(offset, 0, text)
    textWidget.caretOffset = offset + text.length

    codeSuggestion?.let {
      telemetryService.send(it, TelemetryAction.SUGGESTION_ACCEPTED)
    }
  }

  fun cancelCodeSuggestion() {
    try {
      job?.cancel()
      annotationManager.hide()
      currentDisplay.syncExec { codeSuggestionsRenderer.clear() }
    } catch (e: Exception) {
      logger.error("Error canceling code suggestion.", e)
    }
  }

  fun rejectCodeSuggestion() {
    try {
      codeSuggestionsRenderer.reject()
      annotationManager.hide()

      codeSuggestion?.let {
        telemetryService.send(it, TelemetryAction.SUGGESTION_REJECTED)
      }
    } catch (e: Exception) {
      logger.error("Error rejecting code suggestion.", e)
    }
  }

  fun isCodeSuggestionDisplayed() = codeSuggestionsRenderer.isCodeSuggestionDisplayed()

  fun dispose() {
    try {
      codeSuggestionsRenderer.dispose()
      annotationManager.hide()
      DocumentUndoManagerRegistry.getDocumentUndoManager(document).removeDocumentUndoListener(this)
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

    return codeSuggestionsIsEnabled && !codeSuggestionsApiInError && !isCursorAfterBracketPair()
  }

  private fun isCursorAfterBracketPair(): Boolean {
    try {
      val offset = textWidget.caretOffset
      if (offset < 2) return false

      val precedingChars = document.get(offset - 2, 2)
      return BLOCKED_BRACKET_PAIRS.contains(precedingChars)
    } catch (e: Exception) {
      logger.warn("Error checking bounding characters.", e)
      return false
    }
  }

  companion object {
    private val BLOCKED_BRACKET_PAIRS = setOf("[]", "{}", "()")
    private val KEY_PRESS_DEBOUNCE = 150.milliseconds
    private val ARROW_KEYS = listOf(SWT.ARROW_RIGHT, SWT.ARROW_LEFT, SWT.ARROW_UP, SWT.ARROW_DOWN)
  }
}
