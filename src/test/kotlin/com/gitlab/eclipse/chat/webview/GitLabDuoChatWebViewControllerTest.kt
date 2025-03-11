package com.gitlab.eclipse.chat.webview

import com.gitlab.eclipse.chat.context.CurrentFileContextProvider
import com.gitlab.eclipse.chat.services.InsertCodeSnippetService
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.FileContext
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.currentDisplay
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.eclipse.swt.SwtCallable
import org.eclipse.ui.IWorkbench
import org.eclipse.ui.texteditor.ITextEditor

class GitLabDuoChatWebViewControllerTest : DescribeSpec({
  val platformUtils = mockk<PlatformUtils>()
  val textEditor = mockk<ITextEditor>()
  val workbench = mockk<IWorkbench>(relaxed = true)

  val currentFileContextProvider = mockk<CurrentFileContextProvider>()
  val gitlabDuoChatWebViewClient = mockk<GitLabDuoChatWebViewClient>(relaxUnitFun = true)
  val insertCodeSnippetService = mockk<InsertCodeSnippetService>(relaxUnitFun = true)

  val controller = GitLabDuoChatWebViewController(
    platformUtils,
    currentFileContextProvider,
    gitlabDuoChatWebViewClient,
    insertCodeSnippetService
  )

  extensions(LoggingKotestExtension)

  beforeSpec { mockkStatic("com.gitlab.eclipse.utils.DisplayKt") }

  beforeEach {
    every {
      currentDisplay.syncCall<FileContext, Exception>(any())
    } answers {
      firstArg<SwtCallable<FileContext, Exception>>().call()
    }

    every { platformUtils.getWorkbench() } returns workbench
  }

  afterEach { clearAllMocks() }

  afterSpec { unmockkAll() }

  it("should return null when there is no active text editor") {
    every { platformUtils.getActiveTextEditor() } returns null

    val result = controller.getCurrentFileContext()

    result shouldBe null
  }

  it("should return file context when there is an active text editor") {
    every { platformUtils.getActiveTextEditor() } returns textEditor
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

  describe("focusChange") {
    it("should forward the focus update to the client") {
      val notification = FocusChangeNotification(true)

      controller.focusChange(notification)

      verify { gitlabDuoChatWebViewClient.updateFocus(true) }
    }
  }

  describe("insertCodeSnippet") {
    it("should insert code snippet") {
      val snippet = "println(\"Hello\")"

      controller.insertCodeSnippet(InsertCodeSnippetNotification(snippet))

      verify { insertCodeSnippetService.insertCodeSnippet(snippet) }
    }
  }

  describe("openLink") {
    it("should open link in external browser") {
      val url = "https://example.com"
      val notification = OpenLinkNotification(url)

      controller.openLink(notification)

      verify { workbench.browserSupport.externalBrowser.openURL(any()) }
    }
  }
})
