package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.logger
import org.eclipse.swt.custom.StyledText
import org.eclipse.ui.IEditorReference
import org.eclipse.ui.IPartListener2
import org.eclipse.ui.IWorkbenchPartReference
import org.eclipse.ui.texteditor.ITextEditor

@Suppress("ParameterListWrapping")
internal class CodeSuggestionsManager(
  private val platformUtils: PlatformUtils,
  private val createCodeSuggestionsSession: (StyledText) -> CodeSuggestionsSession // Lazily inject a CodeSuggestionsSession
) {
  private val logger = logger<CodeSuggestionsManager>()
  private val editorSessions = mutableMapOf<ITextEditor, CodeSuggestionsSession>()

  init {
    platformUtils.getAllPages().forEach { page ->
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
      val editor = checkNotNull(platformUtils.getActiveTextEditor())
      val textWidget = checkNotNull(platformUtils.getActiveTextWidget())

      if (!editorSessions.contains(editor)) {
        val newSession = createCodeSuggestionsSession(textWidget)
        editorSessions.put(editor, newSession)
        newSession.start()
      }

      logger.info("Code Suggestions session started for ${editor.title}.")
    } catch (e: Exception) {
      logger.error("Error starting a Code Suggestions session", e)
    }
  }

  fun cancelCodeSuggestion() {
    val editor = platformUtils.getActiveTextEditor()
      ?: return

    editorSessions[editor]?.cancelCodeSuggestion()
    logger.info("Code Suggestions session cancelled for ${editor.title}.")
  }

  private fun endSession(editor: ITextEditor) {
    if (editorSessions.remove(editor)?.dispose() != null) {
      logger.info("Code Suggestions session ended for ${editor.title}.")
    } else {
      logger.info("No Code Suggestions session found for ${editor.title}.")
    }
  }

  fun endAllSessions() {
    editorSessions.values.forEach(CodeSuggestionsSession::dispose)
    editorSessions.clear()
    logger.info("Ended all Code Suggestion sessions.")
  }
}
