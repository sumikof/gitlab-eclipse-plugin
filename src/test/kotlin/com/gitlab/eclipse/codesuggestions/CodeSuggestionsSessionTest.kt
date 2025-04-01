package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.codesuggestions.annotation.CodeSuggestionAnnotationType
import com.gitlab.eclipse.codesuggestions.annotation.CodeSuggestionsSessionAnnotationManager
import com.gitlab.eclipse.codesuggestions.listeners.CodeSuggestionsKeyListener
import com.gitlab.eclipse.codesuggestions.listeners.CodeSuggestionsMouseListener
import com.gitlab.eclipse.codesuggestions.listeners.CodeSuggestionsUndoListener
import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.telemetry.TelemetryService
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
import org.eclipse.jface.text.Position
import org.eclipse.swt.SwtCallable
import org.eclipse.swt.custom.StyledText
import org.eclipse.text.undo.DocumentUndoManager
import org.eclipse.text.undo.DocumentUndoManagerRegistry
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

@OptIn(ExperimentalCoroutinesApi::class)
class CodeSuggestionsSessionTest : DescribeSpec({
  val textWidget = mockk<StyledText>(relaxed = true)

  val document = mockk<IDocument>(relaxed = true)
  val documentUndoManager = mockk<DocumentUndoManager>(relaxUnitFun = true)

  val codeSuggestionsProvider = mockk<CodeSuggestionsProvider>()
  val codeSuggestionsStateService = mockk<CodeSuggestionsStateService>()
  val codeSuggestion = CodeSuggestion(streamId = null, "foo", 123, "sample suggestion")

  val annotationManager = mockk<CodeSuggestionsSessionAnnotationManager>(relaxUnitFun = true)
  val streamingCodeSuggestionsManager = mockk<StreamingCodeSuggestionsManager>(relaxUnitFun = true)
  val telemetryService = mockk<TelemetryService>(relaxUnitFun = true)

  val renderer = mockk<CodeSuggestionsRenderer>(relaxed = true)

  lateinit var coroutineScope: TestScope
  lateinit var session: CodeSuggestionsSession

  extensions(LoggingKotestExtension)

  beforeSpec {
    mockkStatic("com.gitlab.eclipse.utils.DocumentKt")
    mockkStatic("com.gitlab.eclipse.utils.DisplayKt")
    mockkStatic(DocumentUndoManagerRegistry::getDocumentUndoManager)

    startKoin {
      modules(
        module {
          single { codeSuggestionsStateService }
        }
      )
    }
  }

  beforeEach {
    every { currentDisplay.syncExec(any()) } answers { firstArg<Runnable>().run() }
    every { currentDisplay.syncCall<Int, Exception>(any()) } answers { firstArg<SwtCallable<Int, Exception>>().call() }

    every { codeSuggestionsStateService.isEnabled } returns true

    every { textWidget.caretOffset } returns 10
    every { textWidget.addKeyListener(any()) } just Runs
    every { textWidget.removeKeyListener(any()) } just Runs

    every { DocumentUndoManagerRegistry.getDocumentUndoManager(document) } returns documentUndoManager
    every { document.uri } returns "file://file.test"
    every { document.addDocumentListener(any()) } just Runs
    every { document.removeDocumentListener(any()) } just Runs
    every { document.getLineOfOffset(10) } returns 1
    every { document.getLineOffset(1) } returns 0

    coEvery { codeSuggestionsProvider.provideAutomaticSuggestion(any(), any(), any()) } returns codeSuggestion

    every { renderer.isCodeSuggestionDisplayed() } returns false
    every { renderer.dispose() } just Runs

    coroutineScope = TestScope(StandardTestDispatcher())
    session = CodeSuggestionsSession(
      textWidget,
      document,
      codeSuggestionsProvider,
      renderer,
      annotationManager,
      streamingCodeSuggestionsManager,
      telemetryService,
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
        verify {
          documentUndoManager.addDocumentUndoListener(any<CodeSuggestionsUndoListener>())
          document.addDocumentListener(session)
          textWidget.addKeyListener(any<CodeSuggestionsKeyListener>())
          textWidget.addMouseListener(any<CodeSuggestionsMouseListener>())
        }
      }

      it("should handle exceptions during instantiation") {
        every { document.addDocumentListener(any()) } throws RuntimeException("Test exception")

        shouldNotThrow<Exception> {
          CodeSuggestionsSession(
            textWidget,
            document,
            codeSuggestionsProvider,
            renderer,
            annotationManager,
            streamingCodeSuggestionsManager,
            telemetryService,
            coroutineScope
          )
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
        every { sessionSpy.requestCodeSuggestion() } just Runs

        sessionSpy.documentChanged(event)

        verify { sessionSpy.requestCodeSuggestion() }
        verify(exactly = 0) { renderer.clear() }
      }

      it("should not automatically request code suggestions if it should skip next suggestion") {
        session.setSkipNextSuggestion()

        val event = mockk<DocumentEvent> {
          every { text } returns "abc"
        }
        session.documentChanged(event)
        coroutineScope.advanceUntilIdle()
        coVerify(exactly = 0) { codeSuggestionsProvider.provideAutomaticSuggestion(any(), any(), any()) }

        session.documentChanged(event)
        coroutineScope.advanceUntilIdle()
        coVerify(exactly = 1) { codeSuggestionsProvider.provideAutomaticSuggestion("file://file.test", 1, 10) }
      }

      it("should not request code suggestions when text is empty") {
        val event = mockk<DocumentEvent> {
          every { text } returns ""
        }
        session.documentChanged(event)

        coVerify(exactly = 0) { codeSuggestionsProvider.provideAutomaticSuggestion(any(), any(), any()) }
      }

      it("should cancel displayed code suggestions when document is about to change") {
        every { renderer.isCodeSuggestionDisplayed() } returns true
        val event = mockk<DocumentEvent> {
          every { text } returns "abc"
        }

        session.documentAboutToBeChanged(event)

        verify { renderer.clear() }
      }
    }

    describe("requestCodeSuggestion") {
      it("should not request code suggestions when the feature state is disabled") {
        every { codeSuggestionsStateService.isEnabled } returns false

        session.requestCodeSuggestion()

        coVerify(exactly = 0) { codeSuggestionsProvider.provideAutomaticSuggestion(any(), any(), any()) }
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

          coVerify(exactly = 0) { codeSuggestionsProvider.provideAutomaticSuggestion(any(), any(), any()) }
        }
      }

      it("should handle exceptions gracefully") {
        coEvery {
          codeSuggestionsProvider.provideAutomaticSuggestion(
            any(),
            any(),
            any()
          )
        } throws RuntimeException("Test exception")

        shouldNotThrow<Exception> {
          session.requestCodeSuggestion()
        }
      }

      it("should request code suggestions when cursor is not after blocked bracket pairs") {
        every { textWidget.caretOffset } returns 10
        every { document.get(8, 2) } returns "ab"

        session.requestCodeSuggestion()

        coroutineScope.advanceUntilIdle()
        verify { renderer.display(codeSuggestion.text, 10) }
      }

      it("should request code suggestions when cursor is at the beginning of document") {
        every { textWidget.caretOffset } returns 1

        session.requestCodeSuggestion()

        coroutineScope.advanceUntilIdle()
        verify { renderer.display(codeSuggestion.text, 1) }
      }

      it("should handle exceptions when checking for bracket pairs") {
        every { textWidget.caretOffset } returns 10
        every { document.get(8, 2) } throws RuntimeException("Test exception")

        shouldNotThrow<Exception> {
          session.requestCodeSuggestion()
          coroutineScope.advanceUntilIdle()
        }
      }

      it("should request code suggestions when the feature is enabled and not in error") {
        every { textWidget.caretOffset } returns 10

        session.requestCodeSuggestion()

        coroutineScope.advanceUntilIdle()
        verify {
          annotationManager.display(CodeSuggestionAnnotationType.LOADING, 10)
          renderer.display(codeSuggestion.text, 10)
          annotationManager.display(CodeSuggestionAnnotationType.READY, 10)
        }
      }

      it("should use the caret offset when no explicit offset is provided") {
        every { textWidget.caretOffset } returns 10

        session.requestCodeSuggestion()

        coroutineScope.advanceUntilIdle()
        verify { renderer.display(codeSuggestion.text, 10) }
      }

      it("should handle null suggestion") {
        coEvery { codeSuggestionsProvider.provideAutomaticSuggestion(any(), any(), any()) } returns null

        session.requestCodeSuggestion()

        coroutineScope.advanceUntilIdle()
        verify(exactly = 0) { renderer.display(any(), any()) }
        verify { annotationManager.hide() }
      }

      it("should not register the current session as a listener for a non-streaming suggestion") {
        every { textWidget.caretOffset } returns 10
        val nonStreamingCodeSuggestion = CodeSuggestion(null, "trackingId", null, "")
        coEvery {
          codeSuggestionsProvider.provideAutomaticSuggestion(
            any(),
            any(),
            any()
          )
        } returns nonStreamingCodeSuggestion

        session.requestCodeSuggestion()

        verify(exactly = 0) { streamingCodeSuggestionsManager.register(any(), any()) }
      }

      it("should register the current session as a listener for streaming suggestions") {
        every { textWidget.caretOffset } returns 10
        val streamingCodeSuggestion = CodeSuggestion("streamId", "trackingId", optionId = null, text = "")
        coEvery {
          codeSuggestionsProvider.provideAutomaticSuggestion(
            any(),
            any(),
            any()
          )
        } returns streamingCodeSuggestion

        session.requestCodeSuggestion()

        coroutineScope.advanceUntilIdle()
        verify(exactly = 1) {
          renderer.display("", 10)
          streamingCodeSuggestionsManager.register("streamId", session)
        }
        verify(exactly = 0) {
          annotationManager.display(CodeSuggestionAnnotationType.READY, 10)
        }
      }
    }

    describe("acceptCodeSuggestion") {
      beforeEach {
        every { renderer.text } returns "suggested text"
        every { renderer.position } returns mockk<Position> {
          every { getOffset() } returns 10
        }
      }

      it("should update the document and move the caret based on the rendered text") {
        session.acceptCodeSuggestion()

        verify {
          document.replace(10, 0, "suggested text")
          textWidget.caretOffset = 10 + "suggested text".length
        }
      }

      it("should clear the code suggestion ui indicators") {
        session.acceptCodeSuggestion()

        verify {
          renderer.clear()
          annotationManager.hide()
        }
      }

      it("should cancel ongoing streaming code suggestion at its current state") {
        val streamingCodeSuggestion = CodeSuggestion("streamId", "trackingId", optionId = null, text = "")
        coEvery {
          codeSuggestionsProvider.provideAutomaticSuggestion(
            any(),
            any(),
            any()
          )
        } returns streamingCodeSuggestion
        session.requestCodeSuggestion()
        coroutineScope.advanceUntilIdle()

        session.acceptCodeSuggestion()

        verify {
          streamingCodeSuggestionsManager.cancel("streamId")
        }
      }
    }

    describe("cancelCodeSuggestion") {
      it("should clear suggestions") {
        session.cancelCodeSuggestion()

        verify {
          renderer.clear()
          annotationManager.hide()
        }
      }

      it("should cancel ongoing streaming code suggestion") {
        val streamingCodeSuggestion = CodeSuggestion("streamId", "trackingId", optionId = null, text = "")
        coEvery {
          codeSuggestionsProvider.provideAutomaticSuggestion(
            any(),
            any(),
            any()
          )
        } returns streamingCodeSuggestion
        session.requestCodeSuggestion()
        coroutineScope.advanceUntilIdle()

        session.cancelCodeSuggestion()

        verify {
          streamingCodeSuggestionsManager.cancel("streamId")
        }
      }

      it("should handle exceptions gracefully") {
        every { renderer.clear() } throws RuntimeException("Test exception")

        shouldNotThrow<Exception> { session.cancelCodeSuggestion() }
      }
    }

    describe("rejectCodeSuggestion") {
      it("should reject code suggestions") {
        session.rejectCodeSuggestion()

        verify {
          renderer.reject()
          annotationManager.hide()
        }
      }

      it("should cancel ongoing streaming code suggestion") {
        val streamingCodeSuggestion = CodeSuggestion("streamId", "trackingId", optionId = null, text = "")
        coEvery {
          codeSuggestionsProvider.provideAutomaticSuggestion(
            any(),
            any(),
            any()
          )
        } returns streamingCodeSuggestion
        session.requestCodeSuggestion()
        coroutineScope.advanceUntilIdle()

        session.rejectCodeSuggestion()

        verify {
          streamingCodeSuggestionsManager.cancel("streamId")
        }
      }

      it("should handle exceptions gracefully when rejecting") {
        every { renderer.reject() } throws RuntimeException("Test exception")

        shouldNotThrow<Exception> { session.rejectCodeSuggestion() }
      }
    }

    describe("dispose") {
      it("should clean up resources and remove document listener") {
        session.dispose()

        verify {
          renderer.dispose()
          annotationManager.hide()

          document.removeDocumentListener(session)

          documentUndoManager.removeDocumentUndoListener(any<CodeSuggestionsUndoListener>())
          textWidget.removeKeyListener(any<CodeSuggestionsKeyListener>())
          textWidget.removeMouseListener(any<CodeSuggestionsMouseListener>())
        }
      }

      it("should cancel ongoing streaming code suggestion") {
        val streamingCodeSuggestion = CodeSuggestion("streamId", "trackingId", optionId = null, text = "")
        coEvery {
          codeSuggestionsProvider.provideAutomaticSuggestion(
            any(),
            any(),
            any()
          )
        } returns streamingCodeSuggestion
        session.requestCodeSuggestion()
        coroutineScope.advanceUntilIdle()

        session.dispose()

        verify {
          streamingCodeSuggestionsManager.cancel("streamId")
        }
      }

      it("failing to dispose a listener should not prevent disposing the session") {
        every { document.removeDocumentListener(any()) } throws RuntimeException("Test exception")

        shouldNotThrow<Exception> { session.dispose() }

        verify {
          renderer.dispose()
          annotationManager.hide()

          document.removeDocumentListener(session)

          documentUndoManager.removeDocumentUndoListener(any<CodeSuggestionsUndoListener>())
          textWidget.removeKeyListener(any<CodeSuggestionsKeyListener>())
          textWidget.removeMouseListener(any<CodeSuggestionsMouseListener>())
        }
      }
    }

    describe("onSuggestionStreamUpdate") {
      it("should update the renderer with new text when stream of the current suggestion is updated") {
        every { textWidget.caretOffset } returns 10
        val streamingCodeSuggestion = CodeSuggestion("streamId", "trackingId", optionId = null, text = "")
        coEvery {
          codeSuggestionsProvider.provideAutomaticSuggestion(
            any(),
            any(),
            any()
          )
        } returns streamingCodeSuggestion

        session.requestCodeSuggestion()
        coroutineScope.advanceUntilIdle()

        session.onSuggestionStreamUpdate(streamId = "streamId", text = "updated suggestion text")

        verify { renderer.update("updated suggestion text") }
      }

      it("should ignore the stream update when the current suggestion has changed") {
        every { textWidget.caretOffset } returns 10
        val streamingCodeSuggestion = CodeSuggestion("streamId", "trackingId", optionId = null, text = "")
        coEvery {
          codeSuggestionsProvider.provideAutomaticSuggestion(
            any(),
            any(),
            any()
          )
        } returns streamingCodeSuggestion

        session.requestCodeSuggestion()
        coroutineScope.advanceUntilIdle()
        session.cancelCodeSuggestion()

        session.onSuggestionStreamUpdate(streamId = "streamId", text = "updated suggestion text")

        verify(exactly = 0) { renderer.update("updated suggestion text") }
      }
    }

    describe("onSuggestionStreamComplete") {
      it("should request annotation manager to display ready icon when stream is complete") {
        every { renderer.position } returns mockk<Position> {
          every { getOffset() } returns 10
        }

        session.onSuggestionStreamComplete()

        verify { annotationManager.display(CodeSuggestionAnnotationType.READY, 10) }
      }
    }

    describe("cycle suggestions") {
      it("should cycle to next suggestion") {
        every { textWidget.caretOffset } returns 10
        val suggestion1 = CodeSuggestion(null, "track1", 1, "suggestion 1")
        val suggestion2 = CodeSuggestion(null, "track2", 2, "suggestion 2")
        coEvery { codeSuggestionsProvider.provideAutomaticSuggestion(any(), any(), any()) } returns suggestion1
        coEvery { codeSuggestionsProvider.provideInvokedSuggestions(any(), any(), any()) } returns listOf(suggestion2)

        session.requestCodeSuggestion()
        coroutineScope.advanceUntilIdle()

        every { renderer.position } returns mockk<Position> {
          every { getOffset() } returns 10
        }

        session.cycleToNextSuggestion()

        verify { renderer.update("suggestion 2") }
      }

      it("should cycle to previous suggestion") {
        every { textWidget.caretOffset } returns 10
        val suggestion1 = CodeSuggestion(null, "track1", 1, "suggestion 1")
        val suggestion2 = CodeSuggestion(null, "track2", 2, "suggestion 2")
        val suggestion3 = CodeSuggestion(null, "track3", 3, "suggestion 3")
        coEvery { codeSuggestionsProvider.provideAutomaticSuggestion(any(), any(), any()) } returns suggestion1
        coEvery { codeSuggestionsProvider.provideInvokedSuggestions(any(), any(), any()) } returns listOf(
          suggestion2,
          suggestion3
        )

        session.requestCodeSuggestion()
        coroutineScope.advanceUntilIdle()

        every { renderer.position } returns mockk<Position> {
          every { getOffset() } returns 10
        }

        session.cycleToPreviousSuggestion()

        // Should cycle to the last suggestion when going backward from the first
        verify { renderer.update("suggestion 3") }
        verify { telemetryService.send(suggestion3, any()) }
      }

      it("should wrap around when cycling past the end of suggestions") {
        every { textWidget.caretOffset } returns 10
        val suggestion1 = CodeSuggestion(null, "track1", 1, "suggestion 1")
        val suggestion2 = CodeSuggestion(null, "track2", 2, "suggestion 2")
        coEvery { codeSuggestionsProvider.provideAutomaticSuggestion(any(), any(), any()) } returns suggestion1
        coEvery { codeSuggestionsProvider.provideInvokedSuggestions(any(), any(), any()) } returns listOf(suggestion2)

        session.requestCodeSuggestion()
        coroutineScope.advanceUntilIdle()

        every { renderer.position } returns mockk<Position> {
          every { getOffset() } returns 10
        }

        session.cycleToNextSuggestion() // To suggestion 2
        session.cycleToNextSuggestion() // Should wrap back to suggestion 1

        verify(exactly = 1) { renderer.update("suggestion 2") }
        verify(exactly = 1) { renderer.update("suggestion 1") }
      }

      it("should do nothing if there's only one suggestion") {
        every { textWidget.caretOffset } returns 10
        val suggestion1 = CodeSuggestion(null, "track1", 1, "suggestion 1")
        coEvery { codeSuggestionsProvider.provideAutomaticSuggestion(any(), any(), any()) } returns suggestion1
        coEvery { codeSuggestionsProvider.provideInvokedSuggestions(any(), any(), any()) } returns emptyList()

        session.requestCodeSuggestion()
        coroutineScope.advanceUntilIdle()

        every { renderer.position } returns mockk<Position> {
          every { getOffset() } returns 10
        }

        session.cycleToNextSuggestion()

        verify(exactly = 0) { renderer.update(any()) }
      }

      // add test to ignore cycle request if code suggestion is still streaming
      it("should ignore cycle request if code suggestion is still streaming") {
        val streamingCodeSuggestion = CodeSuggestion("streamId", "trackingId", optionId = null, text = "")
        coEvery {
          codeSuggestionsProvider.provideAutomaticSuggestion(
            any(),
            any(),
            any()
          )
        } returns streamingCodeSuggestion

        session.requestCodeSuggestion()
        coroutineScope.advanceUntilIdle()

        session.cycleToNextSuggestion()

        verify(exactly = 0) { renderer.update(any()) }
        verify(exactly = 0) { streamingCodeSuggestionsManager.cancel(any()) }
      }
    }
  }
})
