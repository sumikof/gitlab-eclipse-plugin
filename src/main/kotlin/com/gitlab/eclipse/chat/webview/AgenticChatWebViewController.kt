package com.gitlab.eclipse.chat.webview

import com.gitlab.eclipse.chat.context.CurrentFileContextProvider
import com.gitlab.eclipse.chat.services.InsertCodeSnippetService
import com.gitlab.eclipse.lsp.FileContext
import com.gitlab.eclipse.lsp.plugins.PluginController
import com.gitlab.eclipse.lsp.plugins.annotations.PluginNotification
import com.gitlab.eclipse.lsp.plugins.annotations.PluginRequest
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.logger

/**
 * Handles webview-to-host messages from the Agentic Duo Chat webview (`agentic-duo-chat`).
 *
 * Registers the same shared handler set as the classic controller, mirroring
 * `registerDuoChatHandlers` in gitlab-workflow which wires both webview ids identically.
 *
 * Host-to-webview push for Agentic chat is not implemented yet (deferred slice), so this
 * controller has no [GitLabDuoChatWebViewClient]: `focusChange`/`appReady` are accepted
 * without side effects. Agentic focus must never drive the classic client's push queue —
 * [GitLabDuoChatWebViewClient] is classic-only (its `pluginId` is `duo-chat-v2`).
 */
class AgenticChatWebViewController(
  platformUtils: PlatformUtils,
  currentFileContextProvider: CurrentFileContextProvider,
  insertCodeSnippetService: InsertCodeSnippetService
) : PluginController("agentic-duo-chat") {
  private val logger by lazy { logger<AgenticChatWebViewController>() }

  private val handlers = ChatWebViewMessageHandlers(
    platformUtils,
    currentFileContextProvider,
    insertCodeSnippetService
  )

  @PluginRequest("getCurrentFileContext")
  fun getCurrentFileContext(): FileContext? = handlers.getCurrentFileContext()

  @PluginNotification("showMessage")
  fun showMessage(notification: ShowMessageNotification) = handlers.showMessage(notification)

  @PluginNotification("appReady")
  fun appReady() = Unit

  @PluginNotification("insertCodeSnippet")
  fun insertCodeSnippet(notification: InsertCodeSnippetNotification) = handlers.insertCodeSnippet(notification)

  @PluginNotification("focusChange")
  fun focusChange(notification: FocusChangeNotification) {
    // Intentionally does not update any push-queue focus state (host-to-webview push is deferred).
    logger.info("Agentic chat focus changed: isFocused=${notification.isFocused}")
  }

  @PluginNotification("openLink")
  fun openLink(notification: OpenLinkNotification) = handlers.openLink(notification)

  @PluginNotification("openUrl")
  fun openUrl(notification: OpenLinkNotification) = handlers.openLink(notification)

  @PluginNotification("copyCodeSnippet")
  fun copyCodeSnippet(notification: CopyCodeSnippetNotification) = handlers.copyCodeSnippet(notification)

  @PluginNotification("copyMessage")
  fun copyMessage(notification: CopyMessageNotification) = handlers.copyMessage(notification)
}
