package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.codesuggestions.annotation.CodeSuggestionAnnotationType
import com.gitlab.eclipse.codesuggestions.annotation.CodeSuggestionsSessionAnnotationManager
import com.gitlab.eclipse.codesuggestions.listeners.*
import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.codesuggestions.tooltip.CodeSuggestionsTooltip
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

  // Tracks whether we've attempted to load additional suggestions beyond the initial one
  private var hasLoadedAdditionalSuggestions = false

  private val undoListener = CodeSuggestionsUndoListener(document, this)
  private val keyListener = CodeSuggestionsKeyListener(textWidget, this)
  private val mouseListener = CodeSuggestionsMouseListener(textWidget, this)
  private val caretListener = CodeSuggestionsCaretListener(textWidget, this)
  private val codeSuggestionsTooltip = CodeSuggestionsTooltip(textWidget, this)

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
        codeSuggestionsRenderer.display(currentSuggestion?.text.orEmpty(), textWidget.caretOffset + event.text.length)
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

  /**
   * Updates the tooltip with current suggestion information
   * Called after suggestion navigation or when suggestions are loaded
   */
  fun updateTooltip() {
    val position = codeSuggestions.currentIndex + 1
    val totalCount = codeSuggestions.size
    codeSuggestionsTooltip.updateSuggestionDisplay(position, totalCount, hasLoadedAdditionalSuggestions)
  }

  fun requestCodeSuggestion() {
    try {
      if (!isEnabled()) return

      codeSuggestionsTooltip.hide()

      job?.cancel()
      job = coroutineScope.launch {
        delay(KEY_PRESS_DEBOUNCE)

        val offset = currentDisplay.syncCall<Int, Exception> { textWidget.caretOffset }
        val line = document.getLineOfOffset(offset)
        val column = offset - document.getLineOffset(line)

        annotationManager.display(CodeSuggestionAnnotationType.LOADING, offset)

        codeSuggestions.clear()
        hasLoadedAdditionalSuggestions = false

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

  /**
   * Gets information about the current suggestion state
   *
   * @return Triple containing:
   *         - Current position (1-based index)
   *         - Total count of suggestions
   *         - Whether additional suggestions have been loaded
   */
  fun getSuggestionInfo(): Triple<Int, Int, Boolean> {
    return Triple(
      codeSuggestions.currentIndex + 1,
      codeSuggestions.size,
      hasLoadedAdditionalSuggestions
    )
  }

  /**
   * Loads additional code suggestions beyond the initial automatic one
   * If suggestions are already loaded or streaming, completes immediately
   *
   * @param onComplete Callback to execute when loading completes (successful or not)
   */
  fun loadAdditionalSuggestions(onComplete: () -> Unit = { }) {
    if (currentSuggestion?.streamId != null || codeSuggestions.size > 1 || hasLoadedAdditionalSuggestions) {
      hasLoadedAdditionalSuggestions = true
      onComplete()
      return
    }

    job?.cancel()
    job = coroutineScope.launch {
      try {
        val offset = currentDisplay.syncCall<Int, Exception> { textWidget.caretOffset }
        val line = document.getLineOfOffset(offset)
        val column = offset - document.getLineOffset(line)

        annotationManager.display(CodeSuggestionAnnotationType.LOADING, offset)

        codeSuggestionsProvider.provideInvokedSuggestions(
          fileUri = document.uri,
          cursorLine = line,
          cursorColumn = column
        ).let(codeSuggestions::addAll)
      } catch (e: Exception) {
        logger.error("Error loading additional suggestions.", e)
      } finally {
        hasLoadedAdditionalSuggestions = true
        onComplete()
      }
    }
  }

  override fun onSuggestionStreamUpdate(streamId: String, text: String) {
    if (currentSuggestion?.streamId != streamId) return

    codeSuggestionsTooltip.hide()

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

    codeSuggestionsTooltip.hide()

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
    hasLoadedAdditionalSuggestions = false
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
    val textToInsert = StringBuilder().apply {
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

      codeSuggestionsTooltip.hide()

      cancelStreaming()

      annotationManager.hide()
      currentDisplay.syncExec { codeSuggestionsRenderer.clear() }

      codeSuggestions.clear()
      hasLoadedAdditionalSuggestions = false
    } catch (e: Exception) {
      logger.error("Error canceling code suggestion.", e)
    }
  }

  fun rejectCodeSuggestion() {
    try {
      codeSuggestionsRenderer.reject()
      annotationManager.hide()

      codeSuggestionsTooltip.hide()

      currentSuggestion?.let {
        telemetryService.send(it, TelemetryAction.SUGGESTION_REJECTED)

        val stream = it.streamId
        if (stream != null) {
          streamingCodeSuggestionsManager.cancel(stream)
        }
      }

      codeSuggestions.clear()
      hasLoadedAdditionalSuggestions = false
    } catch (e: Exception) {
      logger.error("Error rejecting code suggestion.", e)
    }
  }

  fun isCodeSuggestionDisplayed() = codeSuggestionsRenderer.isCodeSuggestionDisplayed()

  fun getCodeSuggestionPosition() = codeSuggestionsRenderer.documentPosition

  fun setDocumentChangeReason(reason: DocumentChangeReason) {
    documentChangeReason = reason
  }

  fun dispose() {
    try {
      job?.cancel()
      codeSuggestionsTooltip.dispose()
    } catch (e: Exception) {
      logger.error("Error cancelling ongoing code suggestion request.", e)
    }

    cancelStreaming()

    codeSuggestions.clear()
    hasLoadedAdditionalSuggestions = false

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

  /**
   * Cycles to the next or previous suggestion in the list
   * Loads additional suggestions if needed and updates the UI
   *
   * @param direction Direction to cycle (NEXT or PREVIOUS, defaults to NEXT)
   */
  fun cycleCodeSuggestion(
    direction: CycleDirection = CycleDirection.NEXT,
  ) {
    loadAdditionalSuggestions {
      try {
        val suggestion = when (direction) {
          CycleDirection.NEXT -> codeSuggestions.getNext()
          CycleDirection.PREVIOUS -> codeSuggestions.getPrevious()
        }

        if (suggestion != null) {
          currentDisplay.syncExec {
            val offset = currentDisplay.syncCall<Int, Exception> { textWidget.caretOffset }
            codeSuggestionsRenderer.update(suggestion.text)
            annotationManager.display(CodeSuggestionAnnotationType.READY, offset)
          }

          telemetryService.send(suggestion, TelemetryAction.SUGGESTION_SHOWN)
        }
      } catch (e: Exception) {
        logger.error("Error cycling code suggestion.", e)
      } finally {
        updateTooltip()
      }
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
