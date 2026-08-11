package com.gitlab.eclipse.utils

import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.ITextSelection
import org.eclipse.jface.text.ITextViewer
import org.eclipse.swt.custom.StyledText
import org.eclipse.swt.widgets.Display
import org.eclipse.ui.IWorkbench
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.texteditor.ITextEditor

/**
 * Workbench lookups shared across the plugin.
 *
 * [workbenchProvider] and [isUiThread] are constructor lambdas with production defaults (same
 * pattern as `NotificationUtils.show`'s seams) so the editor resolution below is reachable in a
 * headless test without standing up a real workbench or display: a defaulted lambda body only
 * executes when invoked, so tests that inject fakes never load [Display].
 */
class PlatformUtils(
  private val workbenchProvider: () -> IWorkbench = { PlatformUI.getWorkbench() },
  private val isUiThread: () -> Boolean = { Display.getCurrent() != null },
) {
  fun getWorkbench(): IWorkbench = workbenchProvider()

  fun getTextWidget(editor: ITextEditor): StyledText? = editor.getAdapter(ITextViewer::class.java)?.textWidget

  /**
   * The active editor as an [ITextEditor], or null when there is none.
   *
   * On the UI thread, resolved through `IEditorPart.getAdapter` before the direct cast (same
   * order as [com.gitlab.eclipse.ci.lint.ActiveEditorContent.of]): a multi-page editor — a
   * form-based editor whose source tab is a text editor — is not itself an [ITextEditor] but
   * adapts to one, so a bare cast would report "no text editor" for an editor the user is
   * plainly typing in.
   *
   * Off the UI thread only the throw-free direct cast runs: `getAdapter` executes arbitrary
   * adapter-factory code, and callers include coroutines on the SHARED plain-`Job` scope
   * (`GitLabLanguageServerOpenFilesService`) with no catch — one escape would cancel that scope
   * for the session. The platform makes the same call: `MultiPageEditorPart.getAdapter` gates
   * its nested-editor delegation on `Display.getCurrent() != null`, so off-thread the adapter
   * path yields nothing for multi-page editors anyway.
   */
  fun getActiveTextEditor(): ITextEditor? {
    val activeWorkbench = getWorkbench().activeWorkbenchWindow
      ?: getWorkbench().workbenchWindows.firstOrNull { it.activePage?.activeEditor != null }

    val editor = activeWorkbench?.activePage?.activeEditor ?: return null

    return if (isUiThread()) {
      editor.getAdapter(ITextEditor::class.java) ?: (editor as? ITextEditor)
    } else {
      editor as? ITextEditor
    }
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
