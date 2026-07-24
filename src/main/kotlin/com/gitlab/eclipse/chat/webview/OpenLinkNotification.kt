package com.gitlab.eclipse.chat.webview

/**
 * Payload for the `openLink` and `openUrl` webview notifications.
 *
 * Mirrors `OpenLinkParams` in gitlab-workflow (`src/common/webview/duo_chat/duo_chat_handlers.ts`):
 * classic Duo Chat sends `openLink` with `href`, Agentic Duo Chat sends `openUrl` with `url`.
 */
data class OpenLinkNotification(val href: String? = null, val url: String? = null)
