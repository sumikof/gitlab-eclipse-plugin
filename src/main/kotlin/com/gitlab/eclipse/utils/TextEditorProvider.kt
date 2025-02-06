package com.gitlab.eclipse.utils

import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.ITextSelection
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.texteditor.ITextEditor

class TextEditorProvider {
  fun getActiveTextEditor(): ITextEditor? {
    val activeWorkbench = PlatformUI.getWorkbench().activeWorkbenchWindow
      ?: PlatformUI.getWorkbench().workbenchWindows.firstOrNull { it.activePage?.activeEditor != null }

    return activeWorkbench?.activePage?.activeEditor as? ITextEditor
  }

  fun getActiveDocument(): IDocument? {
    val textEditor = getActiveTextEditor() ?: return null
    val editorInput = textEditor.editorInput ?: return null

    return textEditor.documentProvider.getDocument(editorInput)
  }

  fun getActiveSelection(): ITextSelection? {
    val textEditor = getActiveTextEditor() ?: return null

    return textEditor.selectionProvider.selection as? ITextSelection
  }
}
