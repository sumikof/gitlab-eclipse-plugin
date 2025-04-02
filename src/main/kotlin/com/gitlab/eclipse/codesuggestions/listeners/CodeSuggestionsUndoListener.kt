package com.gitlab.eclipse.codesuggestions.listeners

import com.gitlab.eclipse.codesuggestions.CodeSuggestionsSession
import com.gitlab.eclipse.codesuggestions.DocumentChangeReason
import com.gitlab.eclipse.utils.logger
import org.eclipse.jface.text.IDocument
import org.eclipse.text.undo.DocumentUndoEvent
import org.eclipse.text.undo.DocumentUndoManagerRegistry
import org.eclipse.text.undo.IDocumentUndoListener

class CodeSuggestionsUndoListener(
  private val document: IDocument,
  private val session: CodeSuggestionsSession
) : IDocumentUndoListener {
  companion object {
    private val ABOUT_TO_EVENTS = listOf(DocumentUndoEvent.ABOUT_TO_UNDO, DocumentUndoEvent.ABOUT_TO_REDO)
  }

  private val logger by lazy { logger<CodeSuggestionsUndoListener>() }

  init {
    DocumentUndoManagerRegistry.getDocumentUndoManager(document).addDocumentUndoListener(this)
  }

  override fun documentUndoNotification(event: DocumentUndoEvent) {
    if (event.eventType in ABOUT_TO_EVENTS) {
      session.setDocumentChangeReason(DocumentChangeReason.UNDO)
    }
  }

  fun dispose() {
    try {
      DocumentUndoManagerRegistry.getDocumentUndoManager(document).removeDocumentUndoListener(this)
    } catch (e: Exception) {
      logger.error("Error disposing code suggestions undo listener.", e)
    }
  }
}
