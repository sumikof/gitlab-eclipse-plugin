package com.gitlab.eclipse.utils

import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.ITextSelection
import org.eclipse.jface.text.ITextViewer
import org.eclipse.swt.custom.StyledText
import org.eclipse.ui.IWorkbench
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.texteditor.ITextEditor

/**
 * Workbench lookups shared across the plugin.
 *
 * [workbenchProvider] is a constructor lambda with a production default so the editor resolution
 * below is reachable in a headless test without standing up a real workbench or display.
 */
class PlatformUtils(
  private val workbenchProvider: () -> IWorkbench = { PlatformUI.getWorkbench() },
) {
  fun getWorkbench(): IWorkbench = workbenchProvider()

  fun getTextWidget(editor: ITextEditor): StyledText? = editor.getAdapter(ITextViewer::class.java)?.textWidget

  /**
   * The active editor as an [ITextEditor], or null when there is none.
   *
   * Resolved through `IEditorPart.getAdapter` before the direct cast (same order as
   * [com.gitlab.eclipse.ci.lint.ActiveEditorContent.of]): a multi-page editor — a form-based
   * editor whose source tab is a text editor — is not itself an [ITextEditor] but adapts to one,
   * so a bare cast would report "no text editor" for an editor the user is plainly typing in.
   */
  fun getActiveTextEditor(): ITextEditor? {
    val activeWorkbench = getWorkbench().activeWorkbenchWindow
      ?: getWorkbench().workbenchWindows.firstOrNull { it.activePage?.activeEditor != null }

    val editor = activeWorkbench?.activePage?.activeEditor ?: return null

    return editor.getAdapter(ITextEditor::class.java) ?: (editor as? ITextEditor)
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
