package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.*
import org.eclipse.jface.text.ITextViewer
import org.eclipse.ui.texteditor.ITextEditor
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Suppress("MagicNumber")
internal class CodeSuggestionsSession(
  private val coroutineScope: CoroutineScope,
) {
  private val logger = logger<CodeSuggestionsSession>()

  private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

  private var codeSuggestionsRenderer: CodeSuggestionsRenderer? = null
  private var job: Job? = null

  fun start(editor: ITextEditor): Boolean {
    val textWidget = editor.getAdapter(ITextViewer::class.java)
      ?.textWidget
      ?: return false

    // This demos the suggestion being displayed and updated.
    codeSuggestionsRenderer = CodeSuggestionsRenderer(
      textWidget,
      textWidget.caretOffset,
      "Ghost Suggestion\nSecond line of ghost suggestion!\n${LocalTime.now().format(timeFormatter)}"
    )

    job = coroutineScope.launch {
      while (isActive) {
        if (!textWidget.isDisposed) {
          textWidget.display.asyncExec {
            codeSuggestionsRenderer?.update(
              "Ghost Suggestion\nSecond line of ghost suggestion!\n${LocalTime.now().format(timeFormatter)}"
            )
          }
        }

        delay(1000) // 1 second
      }
    }
    return true
  }

  fun dispose() {
    try {
      codeSuggestionsRenderer?.dispose()
      codeSuggestionsRenderer = null

      job?.cancel()
      job = null
    } catch (e: Exception) {
      logger.error("Error disposing code suggestion session", e)
    }
  }
}
