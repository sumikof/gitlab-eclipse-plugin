package com.gitlab.eclipse.chat.context

import com.gitlab.eclipse.lsp.FileContext
import com.gitlab.eclipse.utils.TextEditorProvider
import com.gitlab.eclipse.utils.relativePath
import org.eclipse.core.resources.IFile
import org.eclipse.jface.text.ITextSelection

class CurrentFileContextProvider(
  private val textEditorProvider: TextEditorProvider,
) {
  fun provide(): FileContext? {
    val textEditor = textEditorProvider.getActiveTextEditor() ?: return null

    val editorInput = textEditor.editorInput
    val file = editorInput.getAdapter(IFile::class.java) ?: return null

    val selection = textEditor.selectionProvider.selection as? ITextSelection? ?: return null
    val selectedText = selection.text

    val startOffset = selection.offset
    val endOffset = selection.let { it.offset + it.length }
    val text = textEditor.documentProvider.getDocument(editorInput).get()

    return FileContext(
      fileName = file.relativePath.toString(),
      selectedText = selectedText,
      contentAboveCursor = startOffset.let { text.take(it) },
      contentBelowCursor = endOffset.let { text.drop(it) }
    )
  }
}
