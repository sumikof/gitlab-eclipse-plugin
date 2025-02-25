package com.gitlab.eclipse.utils

import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.ITextSelection
import org.eclipse.jface.text.ITextViewer
import org.eclipse.swt.custom.StyledText
import org.eclipse.ui.IWorkbench
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.texteditor.ITextEditor

class PlatformUtils {
  fun getWorkbench(): IWorkbench = PlatformUI.getWorkbench()

  fun getTextWidget(editor: ITextEditor): StyledText? = editor.getAdapter(ITextViewer::class.java)?.textWidget

  fun getActiveTextEditor(): ITextEditor? {
    val activeWorkbench = getWorkbench().activeWorkbenchWindow
      ?: getWorkbench().workbenchWindows.firstOrNull { it.activePage?.activeEditor != null }

    return activeWorkbench?.activePage?.activeEditor as? ITextEditor
  }

  fun getActiveDocument(): IDocument? {
    return getDocument(getActiveTextEditor() ?: return null)
  }

  fun getDocument(textEditor: ITextEditor): IDocument? {
    val editorInput = textEditor.editorInput ?: return null

    return textEditor.documentProvider.getDocument(editorInput)
  }

  fun getActiveSelection(): ITextSelection? {
    val textEditor = getActiveTextEditor() ?: return null

    return textEditor.selectionProvider.selection as? ITextSelection
  }
}
