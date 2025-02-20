package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.utils.TextEditorProvider
import com.gitlab.eclipse.utils.logger
import org.eclipse.swt.custom.StyledText
import org.eclipse.ui.IEditorReference
import org.eclipse.ui.IPartListener2
import org.eclipse.ui.IWorkbenchPartReference
import org.eclipse.ui.texteditor.ITextEditor

@Suppress("ParameterListWrapping")
internal class CodeSuggestionsManager(
  private val textEditorProvider: TextEditorProvider,
  private val createCodeSuggestionsSession: (StyledText) -> CodeSuggestionsSession // Lazily inject a CodeSuggestionsSession
) {
  private val logger = logger<CodeSuggestionsManager>()
  private val editorSessions = mutableMapOf<ITextEditor, CodeSuggestionsSession>()

  init {
    textEditorProvider.getAllPages().forEach { page ->
      page.addPartListener(
        object : IPartListener2 {
          override fun partClosed(partRef: IWorkbenchPartReference) {
            val editor = (partRef as? IEditorReference)?.getEditor(false)
            if (editor is ITextEditor) {
              endSession(editor)
            }
          }
        }
      )
    }
  }

  fun startSession() {
    try {
      val editor = checkNotNull(textEditorProvider.getActiveTextEditor())
      val textWidget = checkNotNull(textEditorProvider.getActiveTextWidget())

      editorSessions.getOrPut(editor) {
        createCodeSuggestionsSession(textWidget)
      }.start()

      logger.info("Code Suggestions session started for ${editor.title}")
    } catch (e: Exception) {
      logger.error("Error starting a Code Suggestions session", e)
    }
  }

  private fun endSession(editor: ITextEditor) {
    if (editorSessions.remove(editor)?.dispose() != null) {
      logger.info("Code Suggestions session ended for ${editor.title}")
    } else {
      logger.info("No Code Suggestions session found for ${editor.title}")
    }
  }

  fun endAllSessions() {
    editorSessions.values.forEach(CodeSuggestionsSession::dispose)
    editorSessions.clear()
    logger.info("Ended all Code Suggestion sessions.")
  }
}
