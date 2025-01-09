package com.gitlab.eclipse.chat.webview

import com.gitlab.eclipse.chat.context.CurrentFileContextProvider
import com.gitlab.eclipse.lsp.FileContext
import com.gitlab.eclipse.lsp.plugins.annotations.PluginController
import com.gitlab.eclipse.lsp.plugins.annotations.PluginRequest
import com.gitlab.eclipse.utils.TextEditorProvider
import org.eclipse.ui.PlatformUI

@PluginController("duo-chat")
class GitLabDuoChatWebViewController {
  private val textEditorProvider: TextEditorProvider = TextEditorProvider()
  private val currentFileContextProvider: CurrentFileContextProvider = CurrentFileContextProvider()

  @PluginRequest("getCurrentFileContext")
  fun getCurrentFileContext(): FileContext? {
    val textEditor = textEditorProvider.getActiveTextEditor()
      ?: return null

    val result = PlatformUI.getWorkbench().display.syncCall<FileContext, Exception> {
      currentFileContextProvider.provide(textEditor)
    }

    return result
  }
}
