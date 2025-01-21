package com.gitlab.eclipse.chat.webview

import com.gitlab.eclipse.chat.context.CurrentFileContextProvider
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.FileContext
import com.gitlab.eclipse.utils.TextEditorProvider
import com.gitlab.eclipse.utils.currentDisplay
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.eclipse.swt.SwtCallable
import org.eclipse.ui.texteditor.ITextEditor

class GitLabDuoChatWebViewControllerTest : DescribeSpec({
  val textEditorProvider = mockk<TextEditorProvider>()
  val textEditor = mockk<ITextEditor>()

  val currentFileContextProvider = mockk<CurrentFileContextProvider>()

  val controller = GitLabDuoChatWebViewController(textEditorProvider, currentFileContextProvider)

  extensions(LoggingKotestExtension)

  beforeSpec { mockkStatic("com.gitlab.eclipse.utils.DisplayKt") }

  beforeEach {
    every {
      currentDisplay.syncCall<FileContext, Exception>(any())
    } answers {
      firstArg<SwtCallable<FileContext, Exception>>().call()
    }
  }

  afterSpec { unmockkAll() }

  it("should return null when there is no active text editor") {
    every { textEditorProvider.getActiveTextEditor() } returns null

    val result = controller.getCurrentFileContext()

    result shouldBe null
  }

  it("should return file context when there is an active text editor") {
    every { textEditorProvider.getActiveTextEditor() } returns textEditor
    val context = FileContext(
      fileName = "test.txt",
      selectedText = "Hello, World!",
      contentBelowCursor = "",
      contentAboveCursor = ""
    )
    every { currentFileContextProvider.provide(textEditor) } returns context

    val result = controller.getCurrentFileContext()

    result shouldBe context
  }
})
