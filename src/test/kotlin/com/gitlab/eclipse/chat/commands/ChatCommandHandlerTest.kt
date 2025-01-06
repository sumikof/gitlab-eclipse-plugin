package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.lsp.FileContext
import com.gitlab.eclipse.lsp.NOOPLspClient
import com.gitlab.eclipse.lsp.NewPromptRequest
import com.gitlab.eclipse.utils.TextEditorProvider
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.resources.IFile
import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.ITextSelection
import org.eclipse.ui.IEditorInput
import org.eclipse.ui.editors.text.TextEditor

abstract class ChatCommandHandlerTest(
 val commandUnderTest: String,
 val createCommandHandler: (NOOPLspClient, TextEditorProvider) -> ChatCommandHandler
) : DescribeSpec({
 val event = mockk<ExecutionEvent>()

 val textEditor = mockk<TextEditor>()
 val editorInput = mockk<IEditorInput>()
 val file = mockk<IFile>()
 val document = mockk<IDocument>()
 val selection = mockk<ITextSelection>()

 val textEditorProvider = mockk<TextEditorProvider>()
 val lspClient = mockk<NOOPLspClient>(relaxUnitFun = true)

 val handler = createCommandHandler(lspClient, textEditorProvider)

 beforeEach {
  every { textEditorProvider.getActiveTextEditor() } returns textEditor

  every { textEditor.editorInput } returns editorInput
  every { editorInput.getAdapter(IFile::class.java) } returns file

  every { textEditor.selectionProvider.selection } returns selection
  every { textEditor.documentProvider.getDocument(editorInput) } returns document
 }

 afterEach {
  clearAllMocks()
 }

 it("should not send prompt if no files are open") {
  every { editorInput.getAdapter(IFile::class.java) } returns null

  handler.execute(event)

  verify(exactly = 0) { lspClient.send(any()) }
 }

 it("should not send prompt if no text is selected") {
  every { textEditor.selectionProvider.selection } returns null

  handler.execute(event)

  verify(exactly = 0) { lspClient.send(any()) }
 }

 it("should send prompt including current file context") {
  every { file.name } returns "main.kt"
  every { document.get() } returns "abc\ndef\nijk"
  every { selection.offset } returns 4
  every { selection.length } returns 3
  every { selection.text } returns "def"

  handler.execute(event)

  verify(exactly = 1) {
   lspClient.send(
    NewPromptRequest(
     content = commandUnderTest,
     fileContext = FileContext(
      fileName = "main.kt",
      selectedText = "def",
      contentAboveCursor = "abc\n",
      contentBelowCursor = "\nijk"
     )
    )
   )
  }
 }
})