package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.chat.utils.openDuoChatWindow
import com.gitlab.eclipse.chat.webview.GitLabDuoChatWebViewClient
import com.gitlab.eclipse.lsp.FileContext
import com.gitlab.eclipse.lsp.NewPromptRequest
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
  val promptTypeUnderTest: String,
  val createCommandHandler: (GitLabDuoChatWebViewClient, TextEditorProvider) -> ChatCommandHandler
) : DescribeSpec({
  val event = mockk<ExecutionEvent>()

  val textEditor = mockk<TextEditor>()
  val editorInput = mockk<IEditorInput>()
  val file = mockk<IFile>()
  val document = mockk<IDocument>()
  val selection = mockk<ITextSelection>()

  val textEditorProvider = mockk<TextEditorProvider>()
  val gitLabDuoChatWebViewClient = mockk<GitLabDuoChatWebViewClient>()

  val handler = createCommandHandler(gitLabDuoChatWebViewClient, textEditorProvider)

  beforeSpec {
    mockkStatic("com.gitlab.eclipse.utils.FileKt")
    mockkStatic("com.gitlab.eclipse.chat.utils.DuoChatWindowKt")
  }

  beforeEach {
    every { textEditorProvider.getActiveTextEditor() } returns textEditor

    every { textEditor.editorInput } returns editorInput
    every { editorInput.getAdapter(IFile::class.java) } returns file

    every { textEditor.selectionProvider.selection } returns selection
    every { textEditor.documentProvider.getDocument(editorInput) } returns document

    every { openDuoChatWindow() } returns Unit
  }

  afterEach { clearAllMocks() }

  afterSpec { unmockkAll() }

  it("should not send prompt if no files are open") {
    every { editorInput.getAdapter(IFile::class.java) } returns null

    handler.execute(event)

    verify(exactly = 0) { gitLabDuoChatWebViewClient.notify(any(), any()) }
  }

  it("should not send prompt if no text is selected") {
    every { textEditor.selectionProvider.selection } returns null

    handler.execute(event)

    verify(exactly = 0) { gitLabDuoChatWebViewClient.notify(any(), any()) }
  }

  it("should send prompt including current file context") {
    every { file.relativePath } returns Path.fromPortableString("a/main.kt")
    every { document.get() } returns "abc\ndef\nijk"
    every { selection.offset } returns 4
    every { selection.length } returns 3
    every { selection.text } returns "def"

    handler.execute(event)

    verify(exactly = 1) {
      gitLabDuoChatWebViewClient.notify(
        type = "newPrompt",
        payload = NewPromptRequest(
          prompt = promptTypeUnderTest,
          fileContext = FileContext(
            fileName = "a/main.kt",
            selectedText = "def",
            contentAboveCursor = "abc\n",
            contentBelowCursor = "\nijk"
          )
        )
      )
    }
  }
})
