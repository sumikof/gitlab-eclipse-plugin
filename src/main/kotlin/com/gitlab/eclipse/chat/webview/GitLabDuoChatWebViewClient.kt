package com.gitlab.eclipse.chat.webview

import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.plugins.messages.ExtensionToPluginNotification

class GitLabDuoChatWebViewClient(
  private val gitLabLanguageServerWrapper: GitLabLanguageServerWrapper
) {
  private var isReady = false
  private val messagesAwaitingReady: MutableList<ExtensionToPluginNotification> = mutableListOf()

  fun notify(type: String, payload: Any?) {
    val message = ExtensionToPluginNotification(
      pluginId = "duo-chat-v2",
      type = type,
      payload = payload
    )

    when {
      isReady -> gitLabLanguageServerWrapper.languageServer?.pluginNotification(message)
      else -> messagesAwaitingReady += message
    }
  }

  fun markAsReady() {
    isReady = true

    while (messagesAwaitingReady.isNotEmpty()) {
      val message = messagesAwaitingReady.removeFirst()
      gitLabLanguageServerWrapper.languageServer?.pluginNotification(message)
    }
  }
}
