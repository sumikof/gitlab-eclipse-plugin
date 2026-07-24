package com.gitlab.eclipse.chat.webview

import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.plugins.messages.ExtensionToPluginNotification

class GitLabDuoChatWebViewClient(
  private val gitLabLanguageServerWrapper: GitLabLanguageServerWrapper
) {
  private var isFocused = false
  private val messagesAwaitingReady: MutableList<ExtensionToPluginNotification> = mutableListOf()

  fun notify(type: String, payload: Any?) {
    val message = ExtensionToPluginNotification(
      pluginId = ChatWebviewCatalog.CLASSIC_WEBVIEW_ID,
      type = type,
      payload = payload
    )

    when {
      isFocused -> gitLabLanguageServerWrapper.languageServer?.pluginNotification(message)
      else -> messagesAwaitingReady += message
    }
  }

  fun updateFocus(newIsFocused: Boolean) {
    this.isFocused = newIsFocused

    if (this.isFocused) {
      while (messagesAwaitingReady.isNotEmpty()) {
        val message = messagesAwaitingReady.removeFirst()
        gitLabLanguageServerWrapper.languageServer?.pluginNotification(message)
      }
    }
  }
}
