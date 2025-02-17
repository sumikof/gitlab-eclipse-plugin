package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.utils.TextEditorProvider
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.eclipse.ui.*
import org.eclipse.ui.texteditor.ITextEditor

class CodeSuggestionsManagerTest : DescribeSpec({
  val textEditorProvider = mockk<TextEditorProvider>()
  val sessionFactory = mockk<() -> CodeSuggestionsSession>()
  val textEditor = mockk<ITextEditor>()
  val session = mockk<CodeSuggestionsSession>()
  val workbench = mockk<IWorkbenchWindow>()
  val page = mockk<IWorkbenchPage>()

  lateinit var manager: CodeSuggestionsManager

  extensions(LoggingKotestExtension)

  beforeEach {
    mockkStatic(PlatformUI::class)
    every { PlatformUI.getWorkbench().activeWorkbenchWindow } returns workbench
    every { workbench.activePage } returns page
    every { page.addPartListener(any<IPartListener2>()) } just Runs
    every { sessionFactory.invoke() } returns session
    every { textEditor.title } returns "Test Editor"

    manager = CodeSuggestionsManager(textEditorProvider, sessionFactory)
  }

  afterEach {
    clearAllMocks()
  }

  describe("startSession") {
    it("should start a new session when there's an active editor") {
      every { textEditorProvider.getActiveTextEditor() } returns textEditor
      every { session.start(textEditor) } returns true

      val result = manager.startSession()

      result shouldBe true
      verify {
        textEditorProvider.getActiveTextEditor()
        session.start(textEditor)
      }
    }

    it("should return false when there's no active editor") {
      every { textEditorProvider.getActiveTextEditor() } returns null

      val result = manager.startSession()

      result shouldBe false
      verify { textEditorProvider.getActiveTextEditor() }
    }

    it("should not start a new session if one already exists for the editor") {
      every { textEditorProvider.getActiveTextEditor() } returns textEditor
      every { session.start(textEditor) } returns true

      manager.startSession() // Start the first session
      val result = manager.startSession() // Try to start another session

      result shouldBe true
      verify(exactly = 1) { session.start(textEditor) }
    }
  }

  describe("removeSession") {
    it("should remove and dispose the session when editor is closed") {
      val partListener = slot<IPartListener2>()
      every { page.addPartListener(capture(partListener)) } just Runs

      every { textEditorProvider.getActiveTextEditor() } returns textEditor
      every { session.start(textEditor) } returns true
      every { session.dispose() } just Runs

      manager = CodeSuggestionsManager(textEditorProvider, sessionFactory)
      manager.startSession() // Start a session

      val editorRef = mockk<IEditorReference>()
      every { editorRef.getEditor(false) } returns textEditor

      partListener.captured.partClosed(editorRef)

      verify { session.dispose() }
    }
  }
})
