package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.utils.PlatformUtils
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.eclipse.jface.text.IDocument
import org.eclipse.swt.custom.StyledText
import org.eclipse.ui.*
import org.eclipse.ui.texteditor.ITextEditor

class CodeSuggestionsManagerTest : DescribeSpec({
  val textEditor = mockk<ITextEditor>(relaxed = true)
  val session = mockk<CodeSuggestionsSession>(relaxed = true)
  val workbench = mockk<IWorkbench>(relaxed = true)
  val window = mockk<IWorkbenchWindow>(relaxed = true)
  val page = mockk<IWorkbenchPage>(relaxed = true)
  val textWidget = mockk<StyledText>(relaxed = true)
  val document = mockk<IDocument>(relaxed = true)
  val editorRef = mockk<IEditorReference>(relaxed = true)
  val platformUtils = mockk<PlatformUtils>(relaxed = true)

  extensions(LoggingKotestExtension)

  fun createCodeSuggestionsManager(
    isCodeSuggestionsEnabled: Boolean = true,
    createCodeSuggestionsSession: (StyledText, IDocument) -> CodeSuggestionsSession
  ) = CodeSuggestionsManager(
    platformUtils = platformUtils,
    isCodeSuggestionsEnabled = isCodeSuggestionsEnabled,
    createCodeSuggestionsSession = createCodeSuggestionsSession
  )

  beforeEach {
    every { platformUtils.getWorkbench() } returns workbench

    every { workbench.workbenchWindows } returns arrayOf(window)

    every { window.pages } returns arrayOf(page)

    every { platformUtils.getActiveTextEditor() } returns textEditor
    every { platformUtils.getTextWidget(any()) } returns textWidget
    every { platformUtils.getDocument(any()) } returns document

    every { editorRef.getEditor(false) } returns textEditor
    every { textEditor.title } returns "Test Editor"
    every { textWidget.caretOffset } returns 10
  }

  afterEach {
    clearAllMocks()
  }

  afterSpec {
    unmockkAll()
  }

  describe("CodeSuggestionsManager") {
    it("should not set up listeners for workbench, windows and pages if code suggestions are disabled") {
      createCodeSuggestionsManager(
        isCodeSuggestionsEnabled = false,
        createCodeSuggestionsSession = { _, _ -> session }
      )

      verify(exactly = 0) {
        workbench.addWindowListener(any<IWindowListener>())
        window.addPageListener(any<IPageListener>())
        page.addPartListener(any<IPartListener2>())
      }
    }

    it("should set up listeners for workbench, windows and pages during initialization") {
      createCodeSuggestionsManager(
        isCodeSuggestionsEnabled = true,
        createCodeSuggestionsSession = { _, _ -> session }
      )

      verify {
        workbench.addWindowListener(any<IWindowListener>())
        window.addPageListener(any<IPageListener>())
        page.addPartListener(any<IPartListener2>())
      }
    }

    it("should start a Code Suggestion session when editor is opened") {
      val sessionFactory = mockk<(StyledText, IDocument) -> CodeSuggestionsSession>()
      every { sessionFactory.invoke(any(), any()) } returns session

      createCodeSuggestionsManager(
        isCodeSuggestionsEnabled = true,
        createCodeSuggestionsSession = sessionFactory
      )

      val partListener = slot<IPartListener2>()
      verify { page.addPartListener(capture(partListener)) }

      partListener.captured.partOpened(editorRef)

      verify {
        platformUtils.getTextWidget(textEditor)
        platformUtils.getDocument(textEditor)
        sessionFactory.invoke(textWidget, document)
      }
    }

    it("should end the Code Suggestion session when editor is closed") {
      createCodeSuggestionsManager(
        isCodeSuggestionsEnabled = true,
        createCodeSuggestionsSession = { _, _ -> session }
      )

      val partListener = slot<IPartListener2>()
      verify { page.addPartListener(capture(partListener)) }

      partListener.captured.partOpened(editorRef)
      partListener.captured.partClosed(editorRef)

      verify {
        session.dispose()
      }
    }

    it("should end all Code Suggestion sessions") {
      val sessions = listOf(
        mockk<CodeSuggestionsSession>(relaxed = true),
        mockk<CodeSuggestionsSession>(relaxed = true),
        mockk<CodeSuggestionsSession>(relaxed = true)
      )

      var sessionIndex = 0
      val sessionFactory: (StyledText, IDocument) -> CodeSuggestionsSession = { _, _ ->
        sessions[sessionIndex++ % sessions.size]
      }

      val manager = createCodeSuggestionsManager(
        isCodeSuggestionsEnabled = true,
        createCodeSuggestionsSession = sessionFactory
      )

      val partListener = slot<IPartListener2>()
      verify { page.addPartListener(capture(partListener)) }

      repeat(3) {
        val mockEditor = mockk<ITextEditor>(relaxed = true)
        val mockEditorRef = mockk<IEditorReference>(relaxed = true)
        every { mockEditorRef.getEditor(false) } returns mockEditor
        every { platformUtils.getDocument(mockEditor) } returns mockk(relaxed = true)
        every { platformUtils.getTextWidget(mockEditor) } returns mockk(relaxed = true)

        partListener.captured.partOpened(mockEditorRef)
      }

      manager.endAllSessions()

      sessions.forEach { mockSession ->
        verify(exactly = 1) {
          mockSession.dispose()
        }
      }
    }

    it("should reject a code suggestion for the active editor") {
      val manager = createCodeSuggestionsManager(
        isCodeSuggestionsEnabled = true,
        createCodeSuggestionsSession = { _, _ -> session }
      )

      val partListener = slot<IPartListener2>()
      verify { page.addPartListener(capture(partListener)) }

      partListener.captured.partOpened(editorRef)
      manager.rejectCodeSuggestion()

      verify { session.rejectCodeSuggestion() }
    }

    it("should request a code suggestion for an editor with an active session") {
      val manager = createCodeSuggestionsManager(
        isCodeSuggestionsEnabled = true,
        createCodeSuggestionsSession = { _, _ -> session }
      )

      val partListener = slot<IPartListener2>()
      verify { page.addPartListener(capture(partListener)) }

      partListener.captured.partOpened(editorRef)

      manager.requestCodeSuggestion()

      verify { session.requestCodeSuggestion() }
    }

    it("should log a warning when requesting a code suggestion for an editor without an active session") {
      val manager = createCodeSuggestionsManager(
        isCodeSuggestionsEnabled = true,
        createCodeSuggestionsSession = { _, _ -> session }
      )

      val newEditor = mockk<ITextEditor>(relaxed = true)
      every { platformUtils.getActiveTextEditor() } returns newEditor
      every { newEditor.title } returns "New Editor"

      manager.requestCodeSuggestion()

      verify(exactly = 0) { session.requestCodeSuggestion() }
    }

    it("should handle exceptions when setting up platform listeners") {
      createCodeSuggestionsManager(
        isCodeSuggestionsEnabled = true,
        createCodeSuggestionsSession = { _, _ -> session }
      )

      every { platformUtils.getWorkbench() } throws RuntimeException("Test exception")

      shouldNotThrow<Exception> {
        CodeSuggestionsManager(platformUtils) { _, _ -> session }
      }
    }

    describe("isCodeSuggestionDisplayed") {
      it("should return false if no active text editor is found") {
        val manager = createCodeSuggestionsManager(
          isCodeSuggestionsEnabled = true,
          createCodeSuggestionsSession = { _, _ -> session }
        )

        every { platformUtils.getActiveTextEditor() } returns null

        manager.isCodeSuggestionDisplayed() shouldBe false
      }

      it("should return false if no code suggestion session exists") {
        val manager = createCodeSuggestionsManager(
          isCodeSuggestionsEnabled = true,
          createCodeSuggestionsSession = { _, _ -> session }
        )

        val newEditor = mockk<ITextEditor>(relaxed = true)
        every { platformUtils.getActiveTextEditor() } returns newEditor

        manager.isCodeSuggestionDisplayed() shouldBe false
      }

      it("should return true if there is a code suggestion displayed") {
        val manager = createCodeSuggestionsManager(
          isCodeSuggestionsEnabled = true,
          createCodeSuggestionsSession = { _, _ -> session }
        )

        val partListener = slot<IPartListener2>()
        verify { page.addPartListener(capture(partListener)) }
        partListener.captured.partOpened(editorRef)

        every { session.isCodeSuggestionDisplayed() } returns true

        manager.isCodeSuggestionDisplayed() shouldBe true
      }
    }

    describe("acceptCodeSuggestion") {
      it("should not accept code suggestion for an editor without an active session") {
        val manager = createCodeSuggestionsManager(
          isCodeSuggestionsEnabled = true,
          createCodeSuggestionsSession = { _, _ -> session }
        )

        val newEditor = mockk<ITextEditor>(relaxed = true)
        every { platformUtils.getActiveTextEditor() } returns newEditor
        every { newEditor.title } returns "New Editor"

        manager.acceptCodeSuggestion()

        verify(exactly = 0) { session.acceptCodeSuggestion() }
      }

      it("should not accept code suggestion when no active editor is found") {
        val manager = createCodeSuggestionsManager(
          isCodeSuggestionsEnabled = true,
          createCodeSuggestionsSession = { _, _ -> session }
        )

        every { platformUtils.getActiveTextEditor() } returns null

        manager.acceptCodeSuggestion()

        verify(exactly = 0) { session.acceptCodeSuggestion() }
      }

      it("should accept code suggestion for an editor with an active session") {
        val manager = createCodeSuggestionsManager(
          isCodeSuggestionsEnabled = true,
          createCodeSuggestionsSession = { _, _ -> session }
        )

        val partListener = slot<IPartListener2>()
        verify { page.addPartListener(capture(partListener)) }
        partListener.captured.partOpened(editorRef)

        manager.acceptCodeSuggestion()

        verify { session.acceptCodeSuggestion() }
      }
    }
  }
})
