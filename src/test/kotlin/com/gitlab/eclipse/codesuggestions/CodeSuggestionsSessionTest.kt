package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.CodeSuggestionsApiStatusService
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.uri
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.eclipse.jface.text.DocumentEvent
import org.eclipse.jface.text.IDocument
import org.eclipse.swt.SWT
import org.eclipse.swt.custom.StyledText
import org.eclipse.swt.events.KeyEvent
import org.eclipse.swt.events.MouseEvent
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

@OptIn(ExperimentalCoroutinesApi::class)
class CodeSuggestionsSessionTest : DescribeSpec({
  val textWidget = mockk<StyledText>(relaxed = true)

  val document = mockk<IDocument>(relaxed = true)

  val codeSuggestionsProvider = mockk<CodeSuggestionsProvider>(relaxed = true)
  val codeSuggestionsStateService = mockk<CodeSuggestionsStateService>()
  val codeSuggestionsApiStatusService = mockk<CodeSuggestionsApiStatusService>()

  val renderer = mockk<CodeSuggestionsRenderer>(relaxed = true)

  lateinit var coroutineScope: TestScope
  lateinit var session: CodeSuggestionsSession

  extensions(LoggingKotestExtension)

  beforeSpec {
    mockkStatic("com.gitlab.eclipse.utils.DocumentKt")
    mockkStatic("com.gitlab.eclipse.utils.DisplayKt")

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
    every { currentDisplay.syncExec(any()) } answers { firstArg<Runnable>().run() }

    every { codeSuggestionsStateService.isEnabled } returns true
    every { codeSuggestionsApiStatusService.apiStatus.value } returns CodeSuggestionsApiStatusService.ApiStatus.Recovery

    every { textWidget.caretOffset } returns 10
    every { textWidget.addKeyListener(any()) } just Runs
    every { textWidget.removeKeyListener(any()) } just Runs

    coEvery { codeSuggestionsProvider.provide(any(), any(), any()) } returns "Suggestion"

    every { document.uri } returns "file://file.test"
    every { document.addDocumentListener(any()) } just Runs
    every { document.removeDocumentListener(any()) } just Runs
    every { document.getLineOfOffset(10) } returns 1
    every { document.getLineOffset(1) } returns 0

    every { renderer.isCodeSuggestionDisplayed() } returns false
    every { renderer.dispose() } just Runs

    coroutineScope = TestScope(StandardTestDispatcher())
    session = CodeSuggestionsSession(
      textWidget,
      document,
      codeSuggestionsProvider,
      renderer,
      coroutineScope
    )
  }

  afterEach {
    clearAllMocks()
    coroutineScope.cancel()
  }

  afterSpec {
    unmockkAll()
    stopKoin()
  }

  describe("CodeSuggestionsSession") {
    describe("initialization") {
      it("should add itself as key, mouse, and document listeners during instantiation") {
        verify { document.addDocumentListener(session) }
        verify { textWidget.addKeyListener(session) }
        verify { textWidget.addMouseListener(session) }
      }

      it("should handle exceptions during instantiation") {
        every { document.addDocumentListener(any()) } throws RuntimeException("Test exception")

        shouldNotThrow<Exception> {
          CodeSuggestionsSession(textWidget, document, codeSuggestionsProvider, renderer, coroutineScope)
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
        verify(exactly = 0) { renderer.clear() }
      }

      it("should cancel displayed code suggestions when document changes") {
        every { renderer.isCodeSuggestionDisplayed() } returns true
        val event = mockk<DocumentEvent> {
          every { offset } returns 5
          every { text } returns "abc"
        }

        session.documentChanged(event)

        verify { renderer.clear() }
      }

      it("should not request code suggestions when text is empty") {
        val event = mockk<DocumentEvent> {
          every { text } returns ""
        }
        session.documentChanged(event)

        coVerify(exactly = 0) { codeSuggestionsProvider.provide(any(), any(), any()) }
      }
    }

    describe("requestCodeSuggestion") {
      it("should not request code suggestions when the feature state is enabled and API status is in error") {
        every { codeSuggestionsApiStatusService.apiStatus.value } returns CodeSuggestionsApiStatusService.ApiStatus.Error

        session.requestCodeSuggestion()

        coVerify(exactly = 0) { codeSuggestionsProvider.provide(any(), any(), any()) }
      }

      it("should not request code suggestions when the feature state is disabled") {
        every { codeSuggestionsStateService.isEnabled } returns false

        session.requestCodeSuggestion()

        coVerify(exactly = 0) { codeSuggestionsProvider.provide(any(), any(), any()) }
      }

      it("should not request code suggestions when cursor is after bracket pairs") {
        val scenarios = listOf(
          Pair(10, "{}"),
          Pair(20, "[]"),
          Pair(30, "()")
        )

        for ((offset, bracketPair) in scenarios) {
          every { textWidget.caretOffset } returns offset
          every { document.get(offset - 2, 2) } returns bracketPair

          session.requestCodeSuggestion()

          coVerify(exactly = 0) { codeSuggestionsProvider.provide(any(), any(), any()) }
        }
      }

      it("should request code suggestions when cursor is not after blocked bracket pairs") {
        every { textWidget.caretOffset } returns 10
        every { document.get(8, 2) } returns "ab"

        session.requestCodeSuggestion()

        coroutineScope.advanceUntilIdle()

        verify { renderer.display("Suggestion", 10) }
      }

      it("should request code suggestions when cursor is at the beginning of document") {
        every { textWidget.caretOffset } returns 1

        session.requestCodeSuggestion()

        coroutineScope.advanceUntilIdle()

        verify { renderer.display("Suggestion", 1) }
      }

      it("should handle exceptions when checking for bracket pairs") {
        every { textWidget.caretOffset } returns 10
        every { document.get(8, 2) } throws RuntimeException("Test exception")

        session.requestCodeSuggestion()

        coroutineScope.advanceUntilIdle()

        verify { renderer.display("Suggestion", 10) }
      }

      it("should not request code suggestions when suggestions are already displayed") {
        every { renderer.isCodeSuggestionDisplayed() } returns true

        session.requestCodeSuggestion()

        coVerify(exactly = 0) { codeSuggestionsProvider.provide(any(), any(), any()) }
      }

      it("should request code suggestions when the feature is enabled and not in error") {
        coEvery { codeSuggestionsProvider.provide(any(), any(), any()) } returns "Hello"
        every { textWidget.caretOffset } returns 10

        session.requestCodeSuggestion()

        coroutineScope.advanceUntilIdle()

        verify(exactly = 1) { renderer.display("Hello", 10) }
      }

      it("should use the caret offset when no explicit offset is provided") {
        every { textWidget.caretOffset } returns 10

        session.requestCodeSuggestion()

        coroutineScope.advanceUntilIdle()

        verify { renderer.display("Suggestion", 10) }
      }

      it("should handle exceptions gracefully") {
        coEvery { codeSuggestionsProvider.provide(any(), any(), any()) } throws RuntimeException("Test exception")

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
        session.dispose()

        verify { renderer.dispose() }
        verify { document.removeDocumentListener(session) }
        verify { textWidget.removeKeyListener(session) }
      }

      it("should handle exceptions gracefully") {
        every { renderer.dispose() } throws RuntimeException("Test exception")
        every { document.removeDocumentListener(any()) } throws RuntimeException("Test exception")

        shouldNotThrow<Exception> { session.dispose() }
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
