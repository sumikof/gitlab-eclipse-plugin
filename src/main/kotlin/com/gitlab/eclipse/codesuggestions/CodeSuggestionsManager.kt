package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.utils.TextEditorProvider
import com.gitlab.eclipse.utils.logger
import org.eclipse.ui.IEditorReference
import org.eclipse.ui.IPartListener2
import org.eclipse.ui.IWorkbenchPartReference
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.texteditor.ITextEditor

internal class CodeSuggestionsManager(
  private val textEditorProvider: TextEditorProvider,
  private val sessionFactory: () -> CodeSuggestionsSession // Lazily inject a CodeSuggestionsSession
) {
  private val logger = logger<CodeSuggestionsManager>()
  private val editorSessions = mutableMapOf<ITextEditor, CodeSuggestionsSession>()

  private val partListener = object : IPartListener2 {
    override fun partClosed(partRef: IWorkbenchPartReference) {
      (partRef as? IEditorReference)?.getEditor(false)?.let { editor ->
        if (editor is ITextEditor) {
          removeSession(editor)
        }
      }
    }
  }

  init {
    PlatformUI.getWorkbench()
      .activeWorkbenchWindow
      ?.activePage
      ?.addPartListener(partListener)
  }

  fun startSession(): Boolean {
    val editor = textEditorProvider.getActiveTextEditor() ?: run {
      logger.error("No active text editor found")
      return false
    }

    // If already running on this editor, do nothing
    if (editorSessions.containsKey(editor)) {
      logger.info("Code suggestions session already active for editor ${editor.title}")
      return true
    }

    return try {
      val session = sessionFactory()

      if (session.start(editor)) {
        editorSessions[editor] = session
        logger.info("Successfully started code suggestions session for editor ${editor.title}")
      } else {
        logger.error("Failed to start code suggestions for editor ${editor.title}")
      }

      true
    } catch (e: Exception) {
      logger.error("Failed to start code suggestions", e)
      false
    }
  }

  private fun removeSession(editor: ITextEditor) {
    try {
      editorSessions.remove(editor)?.dispose()
      logger.info("Removed code suggestions session for ${editor.title}")
    } catch (e: Exception) {
      logger.error("Error removing code suggestions session for ${editor.title}", e)
    }
  }
}
