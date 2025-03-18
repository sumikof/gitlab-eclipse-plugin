package com.gitlab.eclipse.codesuggestions.listeners

import com.gitlab.eclipse.codesuggestions.CodeSuggestionsSession
import com.gitlab.eclipse.utils.logger
import org.eclipse.jface.text.IDocument
import org.eclipse.text.undo.DocumentUndoEvent
import org.eclipse.text.undo.DocumentUndoManagerRegistry
import org.eclipse.text.undo.IDocumentUndoListener

class CodeSuggestionsUndoListener(
  private val document: IDocument,
  private val session: CodeSuggestionsSession
) : IDocumentUndoListener {
  private val logger by lazy { logger<CodeSuggestionsUndoListener>() }

  init {
    DocumentUndoManagerRegistry.getDocumentUndoManager(document).addDocumentUndoListener(this)
  }

  override fun documentUndoNotification(event: DocumentUndoEvent?) {
    session.setSkipNextSuggestion()
  }

  fun dispose() {
    try {
      DocumentUndoManagerRegistry.getDocumentUndoManager(document).removeDocumentUndoListener(this)
    } catch (e: Exception) {
      logger.error("Error disposing code suggestions undo listener.", e)
    }
  }
}
