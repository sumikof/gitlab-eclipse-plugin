package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.CodeSuggestionsApiStatusService
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.*
import org.eclipse.jface.text.DocumentEvent
import org.eclipse.jface.text.IDocument
import org.eclipse.swt.SWT
import org.eclipse.swt.custom.StyledText
import org.eclipse.swt.events.KeyEvent
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class CodeSuggestionsSessionTest : DescribeSpec({
  val textWidget = mockk<StyledText>(relaxed = true)

  val document = mockk<IDocument>(relaxed = true)

  val codeSuggestionsProvider = mockk<CodeSuggestionsProvider>(relaxed = true)
  val codeSuggestionsStateService = mockk<CodeSuggestionsStateService>()
  val codeSuggestionsApiStatusService = mockk<CodeSuggestionsApiStatusService>()

  val renderer = mockk<CodeSuggestionsRenderer>(relaxed = true)

  lateinit var session: CodeSuggestionsSession

  extensions(LoggingKotestExtension)

  beforeSpec {
    startKoin {
      modules(
        module {
          single { codeSuggestionsStateService }
          single { codeSuggestionsApiStatusService }
        }
      )
    }
  }

  beforeEach {
    every { codeSuggestionsStateService.isEnabled } returns true
    every { codeSuggestionsApiStatusService.apiStatus.value } returns CodeSuggestionsApiStatusService.ApiStatus.Recovery

    every { textWidget.caretOffset } returns 10
    every { textWidget.addKeyListener(any()) } just Runs
    every { textWidget.removeKeyListener(any()) } just Runs

    every { codeSuggestionsProvider.provide() } returns "Suggestion"

    every { document.addDocumentListener(any()) } just Runs
    every { document.removeDocumentListener(any()) } just Runs

    every { renderer.dispose() } just Runs

    session = CodeSuggestionsSession(textWidget, document, codeSuggestionsProvider, renderer)
  }

  afterEach {
    clearAllMocks()
    session.dispose()
  }

  afterSpec {
    unmockkAll()
    stopKoin()
  }

  describe("CodeSuggestionsSession") {
    describe("initialization") {
      it("should add itself as key and document listeners during instantiation") {
        verify { document.addDocumentListener(session) }
        verify { textWidget.addKeyListener(session) }
      }

      it("should handle exceptions during instantiation") {
        every { document.addDocumentListener(any()) } throws RuntimeException("Test exception")

        shouldNotThrow<Exception> {
          CodeSuggestionsSession(textWidget, document, codeSuggestionsProvider, renderer)
        }
      }
    }

    describe("document listener") {
      it("should request code suggestion when document changes") {
        val event = mockk<DocumentEvent> {
          every { offset } returns 5
          every { text } returns "abc"
        }

        val sessionSpy = spyk(session)
        every { sessionSpy.requestCodeSuggestion(any()) } just Runs

        sessionSpy.documentChanged(event)

        verify { sessionSpy.requestCodeSuggestion(8) }
      }

      it("should do nothing in documentAboutToBeChanged") {
        val event = mockk<DocumentEvent>()

        shouldNotThrow<Exception> {
          session.documentAboutToBeChanged(event)
        }
      }
    }

    describe("requestCodeSuggestion") {
      it("should not request code suggestions when key press is filtered") {
        val keyEvent = mockk<KeyEvent>()
        keyEvent.character = SWT.TAB

        session.keyPressed(keyEvent)
        session.requestCodeSuggestion()

        verify(exactly = 0) { renderer.display(any(), any()) }
      }

      it("should use the caret offset when no explicit offset is provided") {
        session.requestCodeSuggestion()

        verify { textWidget.caretOffset }
        verify { renderer.display("Suggestion", 10) }
      }

      it("should handle exceptions gracefully") {
        every { codeSuggestionsProvider.provide() } throws RuntimeException("Test exception")

        shouldNotThrow<Exception> {
          session.requestCodeSuggestion()
        }
      }
    }

    describe("cancelCodeSuggestion") {
      it("should clear suggestions") {
        session.requestCodeSuggestion()
        verify { renderer.display(any(), any()) }

        session.cancelCodeSuggestion()
        verify { renderer.dispose() }
      }

      it("should handle exceptions gracefully") {
        session.requestCodeSuggestion()

        every { renderer.dispose() } throws RuntimeException("Test exception")

        shouldNotThrow<Exception> {
          session.cancelCodeSuggestion()
        }
      }
    }

    describe("dispose") {
      it("should clean up resources and remove document listener") {
        session.requestCodeSuggestion()
        verify { renderer.display(any(), any()) }

        session.dispose()

        verify { renderer.dispose() }
        verify { document.removeDocumentListener(session) }
        verify { textWidget.removeKeyListener(session) }
      }

      it("should handle exceptions gracefully") {
        session.requestCodeSuggestion()
        verify { renderer.display(any(), any()) }

        every { renderer.dispose() } throws RuntimeException("Test exception")
        every { document.removeDocumentListener(any()) } throws RuntimeException("Test exception")

        shouldNotThrow<Exception> {
          session.dispose()
        }
      }
    }

    describe("key listener") {
      it("should mark key press as filtered when TAB is pressed") {
        val keyEvent = mockk<KeyEvent>()
        keyEvent.character = SWT.TAB

        session.keyPressed(keyEvent)

        val event = mockk<DocumentEvent>()
        session.documentChanged(event)

        verify(exactly = 0) { renderer.display(any(), any()) }
      }

      it("should mark key press as filtered when BACKSPACE is pressed") {
        val keyEvent = mockk<KeyEvent>()
        keyEvent.character = SWT.BS

        session.keyPressed(keyEvent)

        val event = mockk<DocumentEvent>()
        session.documentChanged(event)

        verify(exactly = 0) { renderer.display(any(), any()) }
      }

      it("should reset filtered key press flag on key released") {
        val keyEvent = mockk<KeyEvent>()
        keyEvent.character = SWT.TAB

        session.keyPressed(keyEvent)
        session.keyReleased(keyEvent)

        val event = mockk<DocumentEvent> {
          every { offset } returns 5
          every { text } returns "abc"
        }
        session.documentChanged(event)

        verify { renderer.display(any(), any()) }
      }

      it("should clear suggestions when filtered keys are pressed") {
        session.requestCodeSuggestion()
        verify { renderer.display(any(), any()) }

        val keyEvent = mockk<KeyEvent>()
        keyEvent.character = SWT.TAB

        session.keyPressed(keyEvent)

        verify { renderer.clear() }
      }
    }
  }
})
