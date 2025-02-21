package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.utils.PlatformUtils
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.*
import org.eclipse.swt.custom.StyledText
import org.eclipse.ui.IEditorReference
import org.eclipse.ui.IPartListener2
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.ui.texteditor.ITextEditor

class CodeSuggestionsManagerTest : DescribeSpec({
  val textEditor = mockk<ITextEditor>(relaxed = true)
  val session = mockk<CodeSuggestionsSession>(relaxed = true)
  val page = mockk<IWorkbenchPage>(relaxed = true)
  val textWidget = mockk<StyledText>(relaxed = true)
  val editorRef = mockk<IEditorReference>(relaxed = true)
  val platformUtils = mockk<PlatformUtils>(relaxed = true)

  extensions(LoggingKotestExtension)

  beforeEach {
    every { platformUtils.getAllPages() } returns listOf(page)
    every { platformUtils.getActiveTextEditor() } returns textEditor
    every { platformUtils.getActiveTextWidget() } returns textWidget
    every { editorRef.getEditor(false) } returns textEditor
  }

  afterEach {
    clearAllMocks()
  }

  describe("CodeSuggestionsManager") {
    it("should set up part listeners for existing pages") {
      CodeSuggestionsManager(platformUtils) { session }

      verify {
        platformUtils.getAllPages()
        page.addPartListener(any<IPartListener2>())
      }
    }

    it("should start a Code Suggestion session for the active editor") {
      every { textEditor.title } returns "Test Editor"

      val manager = CodeSuggestionsManager(platformUtils) { session }
      manager.startSession()

      verify {
        platformUtils.getActiveTextEditor()
        platformUtils.getActiveTextWidget()
        session.start()
      }
    }

    it("should end the Code Suggestion session when editor is closed") {
      val manager = CodeSuggestionsManager(platformUtils) { session }
      manager.startSession()

      val partListener = slot<IPartListener2>()
      verify { page.addPartListener(capture(partListener)) }

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

      val manager = CodeSuggestionsManager(platformUtils) {
        sessions[sessionIndex++ % sessions.size]
      }

      repeat(3) {
        every { platformUtils.getActiveTextEditor() } returns mockk(relaxed = true)
        every { platformUtils.getActiveTextWidget() } returns mockk(relaxed = true)
        manager.startSession()
      }

      manager.endAllSessions()

      sessions.forEach { session ->
        verify(exactly = 1) {
          session.dispose()
        }
      }
    }
  }
})
