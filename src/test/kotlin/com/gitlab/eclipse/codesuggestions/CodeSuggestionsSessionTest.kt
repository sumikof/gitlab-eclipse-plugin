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
import org.eclipse.swt.events.MouseEvent
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

  var session = CodeSuggestionsSession(textWidget, document, codeSuggestionsProvider, renderer)

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

    every { renderer.isCodeSuggestionDisplayed() } returns false
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
        verify { textWidget.addMouseListener(session) }
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

      it("should not request code suggestions when text is empty") {
        val event = mockk<DocumentEvent> {
          every { text } returns ""
        }
        session.documentChanged(event)

        verify(exactly = 0) { codeSuggestionsProvider.provide() }
      }

      it("should cancel suggestion in documentAboutToBeChanged") {
        val event = mockk<DocumentEvent>()

        session.documentAboutToBeChanged(event)

        verify(exactly = 1) { renderer.clear() }
      }
    }

    describe("requestCodeSuggestion") {
      it("should not request code suggestions when the feature state is enabled and API status is in error") {
        every { codeSuggestionsApiStatusService.apiStatus.value } returns CodeSuggestionsApiStatusService.ApiStatus.Error

        session.requestCodeSuggestion()

        verify(exactly = 0) { codeSuggestionsProvider.provide() }
      }

      it("should not request code suggestions when the feature state is disabled") {
        every { codeSuggestionsStateService.isEnabled } returns false

        session.requestCodeSuggestion()

        verify(exactly = 0) { codeSuggestionsProvider.provide() }
      }

      it("should not request code suggestions when suggestions are already displayed") {
        every { renderer.isCodeSuggestionDisplayed() } returns true

        session.requestCodeSuggestion()

        verify(exactly = 0) { codeSuggestionsProvider.provide() }
      }

      it("should request code suggestions when the feature is enabled and not in error") {
        every { codeSuggestionsProvider.provide() } returns "Hello"
        every { textWidget.caretOffset } returns 10

        session.requestCodeSuggestion()

        verify(exactly = 1) { renderer.display("Hello", 10) }
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
        session.cancelCodeSuggestion()

        verify { renderer.clear() }
      }

      it("should handle exceptions gracefully") {
        every { renderer.clear() } throws RuntimeException("Test exception")

        shouldNotThrow<Exception> { session.cancelCodeSuggestion() }
      }
    }

    describe("rejectCodeSuggestion") {
      it("should reject code suggestions") {
        session.rejectCodeSuggestion()

        verify { renderer.reject() }
      }

      it("should handle exceptions gracefully when rejecting") {
        every { renderer.reject() } throws RuntimeException("Test exception")

        shouldNotThrow<Exception> { session.rejectCodeSuggestion() }
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
      it("should cancel suggestions when arrow keys are pressed") {
        listOf(SWT.ARROW_RIGHT, SWT.ARROW_LEFT, SWT.ARROW_UP, SWT.ARROW_DOWN).forEach { keyCode ->
          val keyEvent = mockk<KeyEvent>()
          keyEvent.keyCode = keyCode
          session.keyPressed(keyEvent)
        }

        verify(exactly = 4) { renderer.clear() }
      }

      it("should not cancel suggestions on other key presses") {
        val keyEvent = mockk<KeyEvent>()
        keyEvent.character = SWT.TAB

        session.keyPressed(keyEvent)

        verify(exactly = 0) { renderer.clear() }
      }
    }

    describe("mouse listener") {
      it("should cancel suggestions when mouse is clicked") {
        val mouseEvent = mockk<MouseEvent>()

        session.mouseDown(mouseEvent)

        verify { renderer.clear() }
      }
    }
  }
})
