package com.gitlab.eclipse.chat.webview

import com.gitlab.eclipse.lsp.WebviewInfo

/**
 * Extracts the known chat webviews (classic and Agentic Duo Chat) from the Language Server's
 * `webviewMetadata()` response, preserving the order in which the Language Server advertises them
 * (the input order, not the declaration order of [CHAT_WEBVIEW_IDS]).
 */
object ChatWebviewCatalog {
  /** Webview id of the classic Duo Chat surface, as advertised by the Language Server. */
  const val CLASSIC_WEBVIEW_ID = "duo-chat-v2"

  /** Webview id of the Agentic Duo Chat surface, as advertised by the Language Server. */
  const val AGENTIC_WEBVIEW_ID = "agentic-duo-chat"

  val CHAT_WEBVIEW_IDS = listOf(CLASSIC_WEBVIEW_ID, AGENTIC_WEBVIEW_ID)

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
