package com.gitlab.eclipse.codesuggestions.listeners

import com.gitlab.eclipse.codesuggestions.CodeSuggestionsSession
import com.gitlab.eclipse.codesuggestions.DocumentChangeReason
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.eclipse.jface.text.IDocument
import org.eclipse.text.undo.DocumentUndoEvent
import org.eclipse.text.undo.DocumentUndoManagerRegistry
import org.eclipse.text.undo.IDocumentUndoManager
import org.junit.jupiter.api.assertDoesNotThrow

class CodeSuggestionsUndoListenerTest : DescribeSpec({
  val document = mockk<IDocument>(relaxUnitFun = true)
  val session = mockk<CodeSuggestionsSession>(relaxUnitFun = true)

  val documentUndoManager = mockk<IDocumentUndoManager>(relaxUnitFun = true)
  lateinit var listener: CodeSuggestionsUndoListener

  extensions(LoggingKotestExtension)

  beforeSpec {
    mockkStatic(DocumentUndoManagerRegistry::getDocumentUndoManager)
  }

  beforeEach {
    every { DocumentUndoManagerRegistry.getDocumentUndoManager(document) } returns documentUndoManager

    listener = CodeSuggestionsUndoListener(document, session)
  }

  afterEach {
    clearAllMocks()
  }

  afterSpec {
    unmockkAll()
  }

  it("should register itself as a document undo listener") {
    verify { documentUndoManager.addDocumentUndoListener(listener) }
  }

  it("should update document change reason when about to undo notification is received") {
    val undoEvent = mockk<DocumentUndoEvent> {
      every { eventType } returns DocumentUndoEvent.ABOUT_TO_UNDO
    }

    listener.documentUndoNotification(undoEvent)

    verify { session.setDocumentChangeReason(DocumentChangeReason.UNDO) }
  }

  it("should update document change reason when about to redo notification is received") {
    val undoEvent = mockk<DocumentUndoEvent> {
      every { eventType } returns DocumentUndoEvent.ABOUT_TO_REDO
    }

    listener.documentUndoNotification(undoEvent)

    verify { session.setDocumentChangeReason(DocumentChangeReason.UNDO) }
  }

  it("should not update document change reason when other notification is received") {
    val undoEvent = mockk<DocumentUndoEvent> {
      every { eventType } returns DocumentUndoEvent.REDONE
    }

    listener.documentUndoNotification(undoEvent)

    verify(exactly = 0) { session.setDocumentChangeReason(any()) }
  }

  it("should unregister itself as a document undo listener when disposed") {
    listener.dispose()

    verify { documentUndoManager.removeDocumentUndoListener(listener) }
  }

  it("should not fail if unregistering the listener throws an exception") {
    every { documentUndoManager.removeDocumentUndoListener(listener) } throws Exception("Test")

    assertDoesNotThrow { listener.dispose() }
  }

  describe("documents without an undo manager (issue #74)") {
    // DocumentUndoManagerRegistry only has a manager for documents that were connect()ed.
    // Editors opened on a non-workspace file (IDE.openEditorOnFileStore, e.g. the MCP config)
    // have none, and the registry returns null. Constructing threw an NPE that propagated out of
    // handler enablement checks and aborted the platform's binding computation.
    it("does not throw when the registry has no undo manager for the document") {
      every { DocumentUndoManagerRegistry.getDocumentUndoManager(document) } returns null

      assertDoesNotThrow { CodeSuggestionsUndoListener(document, session) }
    }

    it("does not throw on dispose either when there is no undo manager") {
      every { DocumentUndoManagerRegistry.getDocumentUndoManager(document) } returns null
      val orphan = CodeSuggestionsUndoListener(document, session)

      assertDoesNotThrow { orphan.dispose() }
    }
  }
})
