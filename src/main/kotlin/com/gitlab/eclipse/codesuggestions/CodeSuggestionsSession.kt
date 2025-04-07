package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.codesuggestions.annotation.CodeSuggestionAnnotationType
import com.gitlab.eclipse.codesuggestions.annotation.CodeSuggestionsSessionAnnotationManager
import com.gitlab.eclipse.codesuggestions.listeners.*
import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.telemetry.TelemetryService
import com.gitlab.eclipse.telemetry.params.TelemetryAction
import com.gitlab.eclipse.utils.CursoredSet
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

  private var documentChangeReason: DocumentChangeReason = DocumentChangeReason.USER_TYPED

  private val codeSuggestions: CursoredSet<CodeSuggestion> = CursoredSet()
  private val currentSuggestion get() = codeSuggestions.getCurrent()

  private val undoListener = CodeSuggestionsUndoListener(document, this)
  private val keyListener = CodeSuggestionsKeyListener(textWidget, this)
  private val mouseListener = CodeSuggestionsMouseListener(textWidget, this)
  private val caretListener = CodeSuggestionsCaretListener(textWidget, this)

  override fun documentAboutToBeChanged(event: DocumentEvent) {
    if (documentChangeReason == DocumentChangeReason.USER_TYPED) {
      caretListener.setCaretMovementReason(CaretMovementReason.USER_TYPED)
    }

    if (documentChangeReason == DocumentChangeReason.SUGGESTION_PARTIALLY_ACCEPTED) {
      caretListener.setCaretMovementReason(CaretMovementReason.SUGGESTION_ACCEPTED)

      cancelStreaming()
      currentDisplay.syncExec { codeSuggestionsRenderer.clear() }
      return
    }

    cancelCodeSuggestion()
  }

  override fun documentChanged(event: DocumentEvent) {
    if (documentChangeReason == DocumentChangeReason.USER_TYPED && event.text.isNotEmpty()) {
      requestCodeSuggestion()
    }

    if (documentChangeReason == DocumentChangeReason.SUGGESTION_PARTIALLY_ACCEPTED) {
      currentDisplay.syncExec {
        codeSuggestionsRenderer.display(currentSuggestion?.text ?: "", textWidget.caretOffset + event.text.length)
      }
    }

    documentChangeReason = DocumentChangeReason.USER_TYPED
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

        displayCurrentSuggestion(offset)
      }
    } catch (e: Exception) {
      logger.error("Error requesting code suggestion.", e)
    }
  }

  override fun onSuggestionStreamUpdate(streamId: String, text: String) {
    if (currentSuggestion?.streamId != streamId) return
    currentSuggestion?.text = text

    currentDisplay.syncExec {
      codeSuggestionsRenderer.update(text)
    }
  }

  override fun onSuggestionStreamComplete() {
    currentDisplay.syncExec {
      val suggestionPosition = codeSuggestionsRenderer.documentPosition
      if (suggestionPosition != null) {
        annotationManager.display(CodeSuggestionAnnotationType.READY, suggestionPosition.offset)
      } else {
        annotationManager.hide()
      }
    }
  }

  fun acceptCodeSuggestion() {
    cancelStreaming()

    val offset = codeSuggestionsRenderer.documentPosition?.offset
      ?: return

    val text = codeSuggestionsRenderer.text
      ?: return

    codeSuggestionsRenderer.clear()
    annotationManager.hide()

    caretListener.setCaretMovementReason(CaretMovementReason.SUGGESTION_ACCEPTED)
    document.replace(offset, 0, text)
    textWidget.caretOffset = offset + text.length

    codeSuggestions.clear()
  }

  fun acceptCodeSuggestionLine() {
    val offset = codeSuggestionsRenderer.documentPosition?.offset
      ?: return

    val text = codeSuggestionsRenderer.text
      ?: return

    val lines = text.lines()
    if (lines.isEmpty()) {
      return
    } else if (lines.size == 1) {
      return acceptCodeSuggestion()
    }

    val currentLine = lines.first()
    var textToInsert = StringBuilder().apply {
      append(currentLine)
      append("\n")
    }

    val subsequentEmptyLines = lines.drop(1).takeWhile { it.isBlank() }
    subsequentEmptyLines.forEach {
      textToInsert.append(it)
      textToInsert.append("\n")
    }

    val nonEmptyLineAfterEmptyLines = lines.getOrNull(1 + subsequentEmptyLines.size)
    if (nonEmptyLineAfterEmptyLines != null) {
      val leadingBlankSequence = nonEmptyLineAfterEmptyLines.takeWhile { it.isWhitespace() }

      if (leadingBlankSequence.isNotEmpty()) {
        textToInsert.append(leadingBlankSequence)
      }
    }

    if (textToInsert.toString() == text) {
      return acceptCodeSuggestion()
    }

    documentChangeReason = DocumentChangeReason.SUGGESTION_PARTIALLY_ACCEPTED

    val remainingText = text.removePrefix(textToInsert.toString())
    currentSuggestion?.text = remainingText

    currentDisplay.syncExec {
      document.replace(offset, 0, textToInsert.toString())
      textWidget.caretOffset = offset + textToInsert.length
    }
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

  fun setDocumentChangeReason(reason: DocumentChangeReason) {
    documentChangeReason = reason
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
    caretListener.dispose()
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

  fun cycleCodeSuggestion(direction: CycleDirection = CycleDirection.NEXT) {
    // If it's a streaming suggestion, don't get more suggestions
    if (currentSuggestion?.streamId != null) return

    try {
      job?.cancel()
      job = coroutineScope.launch {
        val offset = currentDisplay.syncCall<Int, Exception> { textWidget.caretOffset }

        if (codeSuggestions.size <= 1) {
          annotationManager.display(CodeSuggestionAnnotationType.LOADING, offset)

          val added: Boolean
          val line = document.getLineOfOffset(offset)
          val column = offset - document.getLineOffset(line)

          added = codeSuggestionsProvider.provideInvokedSuggestions(
            fileUri = document.uri,
            cursorLine = line,
            cursorColumn = column
          ).let(codeSuggestions::addAll)

          if (!added) return@launch
        }

        when (direction) {
          CycleDirection.NEXT -> codeSuggestions.getNext()
          CycleDirection.PREVIOUS -> codeSuggestions.getPrevious()
        }?.let { suggestion ->
          currentDisplay.syncExec {
            codeSuggestionsRenderer.update(suggestion.text)
            annotationManager.display(CodeSuggestionAnnotationType.READY, offset)
          }

          // Send telemetry with the current suggestion
          telemetryService.send(suggestion, TelemetryAction.SUGGESTION_SHOWN)
        }
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
