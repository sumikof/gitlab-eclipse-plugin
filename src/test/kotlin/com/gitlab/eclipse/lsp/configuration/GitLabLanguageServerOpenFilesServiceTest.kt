package com.gitlab.eclipse.lsp.configuration

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.utils.TextEditorProvider
import com.gitlab.eclipse.utils.uri
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.eclipse.jface.text.BadLocationException
import org.eclipse.jface.text.DocumentEvent
import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.IRegion
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.ui.IWorkbench
import org.eclipse.ui.PlatformUI

class GitLabLanguageServerOpenFilesServiceTest : DescribeSpec({
  val documentUri = "file:///test.kt"
  val document = mockk<IDocument>()
  val workbench = mockk<IWorkbench>(relaxUnitFun = true)

  val languageServer = mockk<GitLabLanguageServer>(relaxed = true)
  val gitLabLanguageServerWrapper = mockk<GitLabLanguageServerWrapper>()

  val textEditorProvider = mockk<TextEditorProvider>()
  val coroutineScope = TestScope(UnconfinedTestDispatcher())

  lateinit var service: GitLabLanguageServerOpenFilesService

  extensions(LoggingKotestExtension)

  beforeSpec {
    mockkStatic(PlatformUI::getWorkbench)
    mockkStatic("com.gitlab.eclipse.utils.DocumentKt")
  }

  beforeEach {
    every { PlatformUI.getWorkbench() } returns workbench
    every { workbench.activeWorkbenchWindow } returns null

    every { gitLabLanguageServerWrapper.languageServer } returns languageServer

    every { document.uri } returns documentUri

    every { document.getLineOfOffset(any<Int>()) } answers { firstArg<Int>() }
    every { document.getLineInformationOfOffset(any<Int>()) } answers {
      mockk<IRegion> {
        every { offset } returns firstArg<Int>()
      }
    }

    service = GitLabLanguageServerOpenFilesService(textEditorProvider, gitLabLanguageServerWrapper, coroutineScope)
  }

  afterEach { clearAllMocks() }

  afterSpec { unmockkAll() }

  describe("didChange") {
    it("should send didChange notification with full document text ") {
      val event = mockk<DocumentEvent> {
        every { this@mockk.document } returns document
        every { modificationStamp } returns 1L
        every { offset } returns 0
        every { length } returns 0
        every { text } returns "test"
      }

      every { document.get() } returns "fallback content"
      every { document.getLineOfOffset(any()) } throws BadLocationException("")

      service.documentChanged(event)

      val sentParams = slot<DidChangeTextDocumentParams>()
      verify {
        languageServer.textDocumentService.didChange(capture(sentParams))
      }

      sentParams.captured.textDocument.uri shouldBe documentUri
      sentParams.captured.textDocument.version shouldBe 1
      sentParams.captured.contentChanges[0].text shouldBe "fallback content"
      sentParams.captured.contentChanges[0].range shouldBe null
    }
  }
})
