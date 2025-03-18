package com.gitlab.eclipse.codesuggestions.listeners

import com.gitlab.eclipse.codesuggestions.CodeSuggestionsSession
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

  it("should set skip next suggestion when undo notification is received") {
    val undoEvent = mockk<DocumentUndoEvent>()
    listener.documentUndoNotification(undoEvent)

    verify { session.setSkipNextSuggestion() }
  }

  it("should unregister itself as a document undo listener when disposed") {
    listener.dispose()

    verify { documentUndoManager.removeDocumentUndoListener(listener) }
  }

  it("should not fail if unregistering the listener throws an exception") {
    every { documentUndoManager.removeDocumentUndoListener(listener) } throws Exception("Test")

    assertDoesNotThrow { listener.dispose() }
  }
})
