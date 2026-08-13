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
    // The registry only has a manager for documents that were connect()ed. An editor opened on a
    // non-workspace file (IDE.openEditorOnFileStore — the MCP config, MR files fetched outside the
    // workspace) has none, and getDocumentUndoManager returns null. Constructing used to throw an
    // NPE here, which propagated out of handler enablement checks and aborted the platform's
    // binding computation, leaving commands stuck disabled (issue #74).
    //
    // Degrading is correct rather than merely safe: a document with no undo manager emits no undo
    // events, so there is nothing this listener could observe on it.
    val undoManager = DocumentUndoManagerRegistry.getDocumentUndoManager(document)
    if (undoManager == null) {
      logger.info("No document undo manager for this editor; undo detection is off for it.")
    } else {
      undoManager.addDocumentUndoListener(this)
    }
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
