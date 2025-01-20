package com.gitlab.eclipse.chat.context

import com.gitlab.eclipse.lsp.FileContext
import com.gitlab.eclipse.utils.relativePath
import org.eclipse.core.resources.IFile
import org.eclipse.jface.text.ITextSelection
import org.eclipse.ui.texteditor.ITextEditor

class CurrentFileContextProvider {
  fun provide(textEditor: ITextEditor): FileContext? {
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
      contentAboveCursor = text.take(startOffset),
      contentBelowCursor = text.drop(endOffset)
    )
  }
}
