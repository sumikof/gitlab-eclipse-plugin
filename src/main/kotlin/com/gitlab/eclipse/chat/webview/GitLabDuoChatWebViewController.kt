package com.gitlab.eclipse.chat.webview

import com.gitlab.eclipse.chat.context.CurrentFileContextProvider
import com.gitlab.eclipse.lsp.FileContext
import com.gitlab.eclipse.lsp.plugins.PluginController
import com.gitlab.eclipse.lsp.plugins.annotations.PluginNotification
import com.gitlab.eclipse.lsp.plugins.annotations.PluginRequest
import com.gitlab.eclipse.utils.TextEditorProvider
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger

class GitLabDuoChatWebViewController(
  private val textEditorProvider: TextEditorProvider = TextEditorProvider(),
  private val currentFileContextProvider: CurrentFileContextProvider = CurrentFileContextProvider()
) : PluginController("duo-chat-v2") {
  private val logger by lazy { logger<GitLabDuoChatWebViewController>() }

  @PluginRequest("getCurrentFileContext")
  fun getCurrentFileContext(): FileContext? {
    val textEditor = textEditorProvider.getActiveTextEditor()
      ?: return null

    return currentDisplay.syncCall<FileContext, Exception> {
      currentFileContextProvider.provide(textEditor)
    }
  }

  @PluginNotification("showMessage")
  fun showMessage(notification: ShowMessageNotification) {
    when (val type = notification.type) {
      "error" -> logger.error(notification.message)
      "warning" -> logger.warn(notification.message)
      "info" -> logger.warn(notification.message)
      else -> logger.warn("Received unknown message type ($type) with content: ${notification.message}")
    }
  }
}
