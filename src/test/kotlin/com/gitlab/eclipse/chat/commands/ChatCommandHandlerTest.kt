package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.lsp.FileContext
import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.NewPromptRequest
import com.gitlab.eclipse.lsp.plugins.messages.ExtensionToPluginNotification
import com.gitlab.eclipse.utils.TextEditorProvider
import com.gitlab.eclipse.utils.relativePath
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.*
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.resources.IFile
import org.eclipse.core.runtime.Path
import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.ITextSelection
import org.eclipse.ui.IEditorInput
import org.eclipse.ui.editors.text.TextEditor

@Suppress("UnnecessaryAbstractClass")
abstract class ChatCommandHandlerTest(
  val commandUnderTest: String,
  val createCommandHandler: (GitLabLanguageServerWrapper, TextEditorProvider) -> ChatCommandHandler
) : DescribeSpec({
  val event = mockk<ExecutionEvent>()

  val textEditor = mockk<TextEditor>()
  val editorInput = mockk<IEditorInput>()
  val file = mockk<IFile>()
  val document = mockk<IDocument>()
  val selection = mockk<ITextSelection>()

  val textEditorProvider = mockk<TextEditorProvider>()

  val languageServerProxy = mockk<GitLabLanguageServer>(relaxUnitFun = true)
  val languageServerWrapper = GitLabLanguageServerWrapper().apply {
    registerLanguageServer(languageServerProxy)
  }

  val handler = createCommandHandler(languageServerWrapper, textEditorProvider)

  beforeSpec { mockkStatic("com.gitlab.eclipse.utils.FileKt") }

  beforeEach {
    every { textEditorProvider.getActiveTextEditor() } returns textEditor

    every { textEditor.editorInput } returns editorInput
    every { editorInput.getAdapter(IFile::class.java) } returns file

    every { textEditor.selectionProvider.selection } returns selection
    every { textEditor.documentProvider.getDocument(editorInput) } returns document
  }

  afterEach { clearAllMocks() }

  afterSpec { unmockkAll() }

  it("should not send prompt if no files are open") {
    every { editorInput.getAdapter(IFile::class.java) } returns null

    handler.execute(event)

    verify(exactly = 0) { languageServerProxy.pluginNotification(any()) }
  }

  it("should not send prompt if no text is selected") {
    every { textEditor.selectionProvider.selection } returns null

    handler.execute(event)

    verify(exactly = 0) { languageServerProxy.pluginNotification(any()) }
  }

  it("should send prompt including current file context") {
    every { file.relativePath } returns Path.fromPortableString("a/main.kt")
    every { document.get() } returns "abc\ndef\nijk"
    every { selection.offset } returns 4
    every { selection.length } returns 3
    every { selection.text } returns "def"

    handler.execute(event)

    verify(exactly = 1) {
      languageServerProxy.pluginNotification(
        ExtensionToPluginNotification(
          pluginId = "duo-chat",
          type = "newPrompt",
          payload = NewPromptRequest(
            prompt = commandUnderTest,
            fileContext = FileContext(
              fileName = "a/main.kt",
              selectedText = "def",
              contentAboveCursor = "abc\n",
              contentBelowCursor = "\nijk"
            )
          )
        )
      )
    }
  }
})
