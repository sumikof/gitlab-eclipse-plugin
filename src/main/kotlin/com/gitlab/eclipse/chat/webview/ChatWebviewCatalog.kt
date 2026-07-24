package com.gitlab.eclipse.chat.webview

import com.gitlab.eclipse.lsp.WebviewInfo

/**
 * Extracts the known chat webviews (classic and Agentic Duo Chat) from the Language Server's
 * `webviewMetadata()` response, preserving the order in which the Language Server advertises them
 * (the input order, not the declaration order of [CHAT_WEBVIEW_IDS]).
 */
object ChatWebviewCatalog {
  val CHAT_WEBVIEW_IDS = listOf("duo-chat-v2", "agentic-duo-chat")

  fun extract(metadata: List<WebviewInfo?>?): List<ChatWebviewEntry> {
    if (metadata == null) return emptyList()
    val seen = mutableSetOf<String>()
    return metadata.asSequence()
      .filterNotNull()
      .filter { it.id in CHAT_WEBVIEW_IDS }
      .mapNotNull { info ->
        val uri = info.uris.firstOrNull() ?: return@mapNotNull null
        ChatWebviewEntry(info.id, info.title, uri)
      }
      .filter { seen.add(it.id) }
      .toList()
  }
}
