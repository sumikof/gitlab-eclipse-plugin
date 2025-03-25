package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.codesuggestions.annotation.CodeSuggestionAnnotationType
import com.gitlab.eclipse.codesuggestions.annotation.CodeSuggestionsSessionAnnotationManager
import com.gitlab.eclipse.codesuggestions.listeners.CodeSuggestionsKeyListener
import com.gitlab.eclipse.codesuggestions.listeners.CodeSuggestionsMouseListener
import com.gitlab.eclipse.codesuggestions.listeners.CodeSuggestionsUndoListener
import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.inject.service
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
import org.eclipse.swt.custom.StyledText
import kotlin.time.Duration.Companion.milliseconds

@Suppress("MagicNumber", "EmptyFunctionBlock", "TooManyFunctions")
class CodeSuggestionsSession(
  private val textWidget: StyledText,
  private val document: IDocument,
  private val codeSuggestionsProvider: CodeSuggestionsProvider,
  private val codeSuggestionsRenderer: CodeSuggestionsRenderer,
  private val annotationManager: CodeSuggestionsSessionAnnotationManager,
  private val streamingCodeSuggestionsManager: StreamingCodeSuggestionsManager,
  private val telemetryService: TelemetryService,
  private val coroutineScope: CoroutineScope,
) : IDocumentListener, StreamingCodeSuggestionsListener {
  private val logger by lazy { logger<CodeSuggestionsSession>() }

  private var job: Job? = null

  private var skipNextSuggestion: Boolean = false
  private var codeSuggestion: CodeSuggestion? = null

  private val keyListener = CodeSuggestionsKeyListener(textWidget, this)
  private val mouseListener = CodeSuggestionsMouseListener(textWidget, this)
  private val undoListener = CodeSuggestionsUndoListener(document, this)

  override fun documentAboutToBeChanged(event: DocumentEvent) {
    cancelCodeSuggestion()
  }

  override fun documentChanged(event: DocumentEvent) {
    if (!skipNextSuggestion && event.text.isNotEmpty()) {
      requestCodeSuggestion(event.offset + event.text.length)
    }

    skipNextSuggestion = false
  }

  init {
    try {
      document.addDocumentListener(this)
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

        val offset = requestOffset ?: currentDisplay.syncCall<Int, Exception> { textWidget.caretOffset }
        val line = document.getLineOfOffset(offset)
        val column = offset - document.getLineOffset(line)

        annotationManager.display(CodeSuggestionAnnotationType.LOADING, offset)

        val suggestion = codeSuggestionsProvider.provide(
          fileUri = document.uri,
          cursorLine = line,
          cursorColumn = column
        ).also { codeSuggestion = it }

        if (suggestion == null) {
          annotationManager.hide()
          return@launch
        }

        currentDisplay.syncExec { codeSuggestionsRenderer.display(suggestion.text, offset) }
        telemetryService.send(suggestion, TelemetryAction.SUGGESTION_SHOWN)

        val streamId = suggestion.streamId
        if (streamId == null) {
          annotationManager.display(CodeSuggestionAnnotationType.READY, offset)
        } else {
          streamingCodeSuggestionsManager.register(streamId, this@CodeSuggestionsSession)
        }
      }
    } catch (e: Exception) {
      logger.error("Error requesting code suggestion.", e)
    }
  }

  override fun onSuggestionStreamUpdate(streamId: String, text: String) {
    if (codeSuggestion?.streamId != streamId) {
      return
    }

    currentDisplay.syncExec {
      codeSuggestionsRenderer.update(text)
    }
  }

  override fun onSuggestionStreamComplete() {
    currentDisplay.syncExec {
      val suggestionPosition = codeSuggestionsRenderer.position

      if (suggestionPosition != null) {
        annotationManager.display(CodeSuggestionAnnotationType.READY, suggestionPosition.offset)
      } else {
        annotationManager.hide()
      }
    }
  }

  fun acceptCodeSuggestion() {
    val offset = codeSuggestionsRenderer.position?.offset
      ?: return

    val text = codeSuggestionsRenderer.text
      ?: return

    codeSuggestionsRenderer.clear()
    annotationManager.hide()

    document.replace(offset, 0, text)
    textWidget.caretOffset = offset + text.length

    codeSuggestion?.let {
      telemetryService.send(it, TelemetryAction.SUGGESTION_ACCEPTED)

      val stream = it.streamId
      if (stream != null) {
        streamingCodeSuggestionsManager.cancel(stream)
      }
    }
  }

  fun cancelCodeSuggestion() {
    try {
      job?.cancel()

      codeSuggestion?.streamId?.let { stream ->
        streamingCodeSuggestionsManager.cancel(stream)
      }

      annotationManager.hide()
      currentDisplay.syncExec { codeSuggestionsRenderer.clear() }

      codeSuggestion = null
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

        val stream = it.streamId
        if (stream != null) {
          streamingCodeSuggestionsManager.cancel(stream)
        }
      }

      codeSuggestion = null
    } catch (e: Exception) {
      logger.error("Error rejecting code suggestion.", e)
    }
  }

  fun isCodeSuggestionDisplayed() = codeSuggestionsRenderer.isCodeSuggestionDisplayed()

  fun setSkipNextSuggestion() {
    skipNextSuggestion = true
  }

  fun dispose() {
    try {
      job?.cancel()
    } catch (e: Exception) {
      logger.error("Error cancelling ongoing code suggestion request.", e)
    }

    codeSuggestion?.streamId?.let { stream ->
      streamingCodeSuggestionsManager.cancel(stream)
    }
    codeSuggestion = null

    try {
      codeSuggestionsRenderer.dispose()
    } catch (e: Exception) {
      logger.error("Error disposing code suggestion session.", e)
    }

    try {
      document.removeDocumentListener(this)
    } catch (e: Exception) {
      logger.error("Error removing document listener.", e)
    }

    annotationManager.hide()
    keyListener.dispose()
    mouseListener.dispose()
    undoListener.dispose()
  }

  private fun isEnabled() = service<CodeSuggestionsStateService>().isEnabled && !isCursorAfterBracketPair()

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
  }
}
