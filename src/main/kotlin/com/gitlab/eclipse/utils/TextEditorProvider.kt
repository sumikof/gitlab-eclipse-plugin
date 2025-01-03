package com.gitlab.eclipse.utils

import org.eclipse.ui.PlatformUI
import org.eclipse.ui.texteditor.ITextEditor

class TextEditorProvider {
    fun getActiveTextEditor(): ITextEditor? {
        return PlatformUI.getWorkbench().activeWorkbenchWindow.activePage.activeEditor as? ITextEditor
    }
}