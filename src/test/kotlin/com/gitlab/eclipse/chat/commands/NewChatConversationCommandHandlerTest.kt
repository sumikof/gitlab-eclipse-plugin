package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.chat.context.CurrentFileContextProvider
import com.gitlab.eclipse.chat.utils.openDuoChatWindowWithClassicPrompt
import com.gitlab.eclipse.lsp.FileContext
import com.gitlab.eclipse.lsp.NewPromptRequest
import com.gitlab.eclipse.utils.PlatformUtils
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.editors.text.TextEditor

class NewChatConversationCommandHandlerTest : DescribeSpec({
  val event = mockk<ExecutionEvent>()
  val textEditor = mockk<TextEditor>()
  val platformUtils = mockk<PlatformUtils>()
  val currentFileContextProvider = mockk<CurrentFileContextProvider>()

  val handler = NewChatConversationCommandHandler(
    platformUtils = platformUtils,
    currentFileContextProvider = currentFileContextProvider
  )

  beforeSpec { mockkStatic("com.gitlab.eclipse.chat.utils.DuoChatWindowKt") }

  beforeEach {
    every { openDuoChatWindowWithClassicPrompt(any()) } returns Unit
    every { platformUtils.getActiveTextEditor() } returns textEditor
  }

  afterEach { clearAllMocks() }
  afterSpec { unmockkAll() }

  describe("execute") {
    it("sends the selected text as file context") {
      val fileContext = FileContext(
        fileName = "a/main.kt",
        selectedText = "def",
        contentAboveCursor = "abc\n",
        contentBelowCursor = "\nijk"
      )
      every { currentFileContextProvider.provide(textEditor) } returns fileContext

      handler.execute(event)

      verify(exactly = 1) {
        openDuoChatWindowWithClassicPrompt(
          NewPromptRequest(prompt = "newConversation", fileContext = fileContext)
        )
      }
    }

    // Guards against leaking the whole file: CurrentFileContextProvider still returns a
    // FileContext when nothing is selected, and that context carries the entire document
    // in contentAboveCursor/contentBelowCursor.
    it("sends no file context when nothing is selected") {
      every { currentFileContextProvider.provide(textEditor) } returns FileContext(
        fileName = "a/main.kt",
        selectedText = "",
        contentAboveCursor = "abc\ndef\nijk",
        contentBelowCursor = ""
      )

      handler.execute(event)

      verify(exactly = 1) {
        openDuoChatWindowWithClassicPrompt(
          NewPromptRequest(prompt = "newConversation", fileContext = null)
        )
      }
    }

    it("sends no file context when no editor is active") {
      every { platformUtils.getActiveTextEditor() } returns null

      handler.execute(event)

      verify(exactly = 1) {
        openDuoChatWindowWithClassicPrompt(
          NewPromptRequest(prompt = "newConversation", fileContext = null)
        )
      }
    }

    it("sends no file context when the editor input does not adapt to a file") {
      every { currentFileContextProvider.provide(textEditor) } returns null

      handler.execute(event)

      verify(exactly = 1) {
        openDuoChatWindowWithClassicPrompt(
          NewPromptRequest(prompt = "newConversation", fileContext = null)
        )
      }
    }

    it("is enabled even without a selection") {
      handler.isEnabled shouldBe true
    }
  }
})
