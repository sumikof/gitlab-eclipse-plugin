package com.gitlab.eclipse.utils

import org.eclipse.ui.PlatformUI
import org.eclipse.ui.texteditor.ITextEditor

class TextEditorProvider {
  fun getActiveTextEditor(): ITextEditor? {
    val activeWorkbench = PlatformUI.getWorkbench().activeWorkbenchWindow
      ?: PlatformUI.getWorkbench().workbenchWindows.firstOrNull { it.activePage?.activeEditor != null }

    return activeWorkbench?.activePage?.activeEditor as? ITextEditor
  }
}
