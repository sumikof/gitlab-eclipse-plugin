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
import com.gitlab.eclipse.utils.CursoredSet
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.utils.uri
import kotlinx.coroutines.*
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
  private val codeSuggestions: CursoredSet<CodeSuggestion> = CursoredSet()
  private val currentSuggestion get() = codeSuggestions.getCurrent()

  private var isFirstSuggestionStreaming: Boolean = false

  private val keyListener = CodeSuggestionsKeyListener(textWidget, this)
  private val mouseListener = CodeSuggestionsMouseListener(textWidget, this)
  private val undoListener = CodeSuggestionsUndoListener(document, this)

  override fun documentAboutToBeChanged(event: DocumentEvent) {
    cancelCodeSuggestion()
  }

  override fun documentChanged(event: DocumentEvent) {
    if (!skipNextSuggestion && event.text.isNotEmpty()) {
      requestCodeSuggestion()
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

  fun requestCodeSuggestion() {
    try {
      if (!isEnabled()) return

      job?.cancel()
      job = coroutineScope.launch {
        delay(KEY_PRESS_DEBOUNCE)

        val offset = currentDisplay.syncCall<Int, Exception> { textWidget.caretOffset }
        val line = document.getLineOfOffset(offset)
        val column = offset - document.getLineOffset(line)

        annotationManager.display(CodeSuggestionAnnotationType.LOADING, offset)

        codeSuggestions.clear()
        val suggestion = codeSuggestionsProvider.provideAutomaticSuggestion(
          fileUri = document.uri,
          cursorLine = line,
          cursorColumn = column
        )

        if (suggestion != null) {
          codeSuggestions.add(suggestion)
        } else {
          annotationManager.hide()
          return@launch
        }

        isFirstSuggestionStreaming = currentSuggestion?.streamId != null

        displayCurrentSuggestion(offset)
      }
    } catch (e: Exception) {
      logger.error("Error requesting code suggestion.", e)
    }
  }

  override fun onSuggestionStreamUpdate(streamId: String, text: String) {
    if (currentSuggestion?.streamId != streamId) return

    currentDisplay.syncExec {
      codeSuggestionsRenderer.update(text)
    }
  }

  override fun onSuggestionStreamComplete() {
    logger.info("Suggestion stream completed, updating UI")
    currentDisplay.syncExec {
      val suggestionPosition = codeSuggestionsRenderer.position

      if (isFirstSuggestionStreaming) {
        isFirstSuggestionStreaming = false

        currentSuggestion?.let { suggestion ->
          val text = codeSuggestionsRenderer.text
          if (suggestion.streamId != null && text != null) {
            suggestion
              .copy(text = text)
              .let {
                codeSuggestions.clear()
                codeSuggestions.add(it)
              }
          }
        }
      }

      if (suggestionPosition != null) {
        annotationManager.display(CodeSuggestionAnnotationType.READY, suggestionPosition.offset)
      } else {
        annotationManager.hide()
      }
    }
  }

  fun acceptCodeSuggestion() {
    currentSuggestion?.let {
      telemetryService.send(it, TelemetryAction.SUGGESTION_ACCEPTED)

      val stream = it.streamId
      if (stream != null) {
        streamingCodeSuggestionsManager.cancel(stream)
      }
    }

    val offset = codeSuggestionsRenderer.position?.offset
      ?: return

    val text = codeSuggestionsRenderer.text
      ?: return

    codeSuggestionsRenderer.clear()
    annotationManager.hide()

    document.replace(offset, 0, text)
    textWidget.caretOffset = offset + text.length

    codeSuggestions.clear()
  }

  fun cancelCodeSuggestion() {
    try {
      job?.cancel()

      cancelStreaming()

      annotationManager.hide()
      currentDisplay.syncExec { codeSuggestionsRenderer.clear() }

      codeSuggestions.clear()
    } catch (e: Exception) {
      logger.error("Error canceling code suggestion.", e)
    }
  }

  fun rejectCodeSuggestion() {
    try {
      codeSuggestionsRenderer.reject()
      annotationManager.hide()

      currentSuggestion?.let {
        telemetryService.send(it, TelemetryAction.SUGGESTION_REJECTED)

        val stream = it.streamId
        if (stream != null) {
          streamingCodeSuggestionsManager.cancel(stream)
        }
      }

      codeSuggestions.clear()
    } catch (e: Exception) {
      logger.error("Error rejecting code suggestion.", e)
    }
  }

  fun isCodeSuggestionDisplayed() = codeSuggestionsRenderer.isCodeSuggestionDisplayed()

  fun cycleToNextSuggestion() {
    cycleCodeSuggestion(1)
  }

  fun cycleToPreviousSuggestion() {
    cycleCodeSuggestion(-1)
  }

  fun setSkipNextSuggestion() {
    skipNextSuggestion = true
  }

  fun dispose() {
    try {
      job?.cancel()
    } catch (e: Exception) {
      logger.error("Error cancelling ongoing code suggestion request.", e)
    }

    cancelStreaming()

    codeSuggestions.clear()

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

  private fun cancelStreaming() {
    try {
      currentSuggestion?.streamId?.let { stream ->
        streamingCodeSuggestionsManager.cancel(stream)
      }
    } catch (e: Exception) {
      logger.error("Error canceling streaming suggestion.", e)
    }
  }

  private fun cycleCodeSuggestion(direction: Int = 1) {
    try {
      // If the first suggestion is still streaming, don't try to get more suggestions
      // Just let the key binding execute naturally
      if (isFirstSuggestionStreaming) {
        logger.info("First suggestion is still streaming, ignoring cycle request")
        return
      }

      // If there's only one suggestion, try to get more suggestions first
      if (codeSuggestions.size <= 1) {
        val offset = codeSuggestionsRenderer.position?.offset ?: return

        annotationManager.display(CodeSuggestionAnnotationType.LOADING, offset)

        var added: Boolean
        runBlocking {
          val line = document.getLineOfOffset(offset)
          val column = offset - document.getLineOffset(line)

          added = codeSuggestionsProvider.provideInvokedSuggestions(
            fileUri = document.uri,
            cursorLine = line,
            cursorColumn = column
          ).let(codeSuggestions::addAll)
        }

        if (!added) return
      }

      val offset = codeSuggestionsRenderer.position?.offset ?: return

      if (direction > 0) {
        codeSuggestions.getNext()
      } else {
        codeSuggestions.getPrevious()
      }

      // Update the UI with the new suggestion
      currentSuggestion?.let { suggestion ->
        logger.info("Cycling to suggestion: ${suggestion.text}")
        currentDisplay.syncExec { codeSuggestionsRenderer.update(suggestion.text) }

        // Send telemetry with the current suggestion
        telemetryService.send(suggestion, TelemetryAction.SUGGESTION_SHOWN)
      }

      // Handle streaming if needed
      val streamId = currentSuggestion?.streamId
      if (streamId == null) {
        annotationManager.display(CodeSuggestionAnnotationType.READY, offset)
      } else {
        streamingCodeSuggestionsManager.register(streamId, this@CodeSuggestionsSession)
      }
    } catch (e: Exception) {
      logger.error("Error cycling code suggestion.", e)
    }
  }

  private fun displayCurrentSuggestion(offset: Int) {
    currentSuggestion?.let { suggestion ->
      currentDisplay.syncExec { codeSuggestionsRenderer.display(suggestion.text, offset) }
      telemetryService.send(suggestion, TelemetryAction.SUGGESTION_SHOWN)
      val streamId = suggestion.streamId
      if (streamId == null) {
        annotationManager.display(CodeSuggestionAnnotationType.READY, offset)
      } else {
        streamingCodeSuggestionsManager.register(streamId, this@CodeSuggestionsSession)
      }
    }
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
