package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.lsp.FileContext
import com.gitlab.eclipse.lsp.NewPromptRequest
import com.gitlab.eclipse.utils.TextEditorProvider
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.resources.IFile
import org.eclipse.jface.text.ITextSelection

abstract class ChatCommandHandler(
    private val command: String,
    private val textEditorProvider: TextEditorProvider = TextEditorProvider()
) : AbstractHandler() {
    override fun execute(event: ExecutionEvent) {
        val textEditor = textEditorProvider.getActiveTextEditor() ?: return

        val editorInput = textEditor.editorInput
        val file = editorInput.getAdapter(IFile::class.java) ?: return

        val selection = textEditor.selectionProvider.selection as? ITextSelection? ?: return
        val selectedText = selection.text

        val startOffset = selection.offset
        val endOffset = selection.let { it.offset + it.length }
        val text = textEditor.documentProvider.getDocument(editorInput).get()

        val request = NewPromptRequest(
            content = command,
            fileContext = FileContext(
                fileName = file.name,
                selectedText = selectedText,
                contentAboveCursor = startOffset.let { text.take(it) },
                contentBelowCursor = endOffset.let { text.drop(it) }
            )
        )

        // TODO: Actually send a request to the language server
        println(request)
    }

    override fun isEnabled(): Boolean {
        val selection = textEditorProvider
            .getActiveTextEditor()
            ?.selectionProvider
            ?.selection as? ITextSelection
            ?: return false

        return selection.text.isNotEmpty()
    }
}