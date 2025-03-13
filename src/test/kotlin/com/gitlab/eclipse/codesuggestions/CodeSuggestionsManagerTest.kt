package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.utils.PlatformUtils
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
    createCodeSuggestionsSession: (ITextEditor) -> CodeSuggestionsSession
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
        createCodeSuggestionsSession = { _ -> session }
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
        createCodeSuggestionsSession = { _ -> session }
      )

      verify {
        workbench.addWindowListener(any<IWindowListener>())
        window.addPageListener(any<IPageListener>())
        page.addPartListener(any<IPartListener2>())
      }
    }

    it("should start a Code Suggestion session when editor is opened") {
      val sessionFactory = mockk<(ITextEditor) -> CodeSuggestionsSession>()
      every { sessionFactory.invoke(any()) } returns session

      createCodeSuggestionsManager(
        isCodeSuggestionsEnabled = true,
        createCodeSuggestionsSession = sessionFactory
      )

      val partListener = slot<IPartListener2>()
      verify { page.addPartListener(capture(partListener)) }

      partListener.captured.partOpened(editorRef)

      verify { sessionFactory.invoke(textEditor) }
    }

    it("should end the Code Suggestion session when editor is closed") {
      createCodeSuggestionsManager(
        isCodeSuggestionsEnabled = true,
        createCodeSuggestionsSession = { _ -> session }
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
      val sessionFactory: (ITextEditor) -> CodeSuggestionsSession = { _ ->
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

    describe("getOrCreateSession") {
      it("should return existing session for editor if it exists") {
        val manager = createCodeSuggestionsManager(
          isCodeSuggestionsEnabled = true,
          createCodeSuggestionsSession = { _ -> session }
        )

        val partListener = slot<IPartListener2>()
        verify { page.addPartListener(capture(partListener)) }
        partListener.captured.partOpened(editorRef)

        val result = manager.getOrCreateSession(textEditor)

        result shouldBe session
      }

      it("should create new session for editor if it doesn't exist") {
        val sessionFactory = mockk<(ITextEditor) -> CodeSuggestionsSession>()
        every { sessionFactory.invoke(any()) } returns session

        val newEditor = mockk<ITextEditor>(relaxed = true)
        every { newEditor.title } returns "New Editor"
        every { platformUtils.getDocument(newEditor) } returns mockk(relaxed = true)
        every { platformUtils.getTextWidget(newEditor) } returns mockk(relaxed = true)

        val manager = createCodeSuggestionsManager(
          isCodeSuggestionsEnabled = true,
          createCodeSuggestionsSession = sessionFactory
        )

        val result = manager.getOrCreateSession(newEditor)

        result shouldBe session
      }
    }
  }
})
