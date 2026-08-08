package com.gitlab.eclipse.chat.webview

import com.gitlab.eclipse.chat.context.CurrentFileContextProvider
import com.gitlab.eclipse.chat.services.InsertCodeSnippetService
import com.gitlab.eclipse.lsp.FileContext
import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.lsp.plugins.PluginController
import com.gitlab.eclipse.lsp.plugins.annotations.PluginNotification
import com.gitlab.eclipse.lsp.plugins.annotations.PluginRequest
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger

/**
 * Handles webview-to-host messages from the Agentic Duo Chat webview (`agentic-duo-chat`).
 *
 * Registers the same shared handler set as the classic controller, mirroring
 * `registerDuoChatHandlers` in gitlab-workflow which wires both webview ids identically.
 *
 * Host-to-webview push for Agentic chat goes through [AgenticChatWebViewClient], never through
 * [GitLabDuoChatWebViewClient], which is classic-only (its `pluginId` is `duo-chat-v2`). Agentic
 * focus drives no push state at all.
 *
 * [onUiThread] is injected as an overload rather than a default argument, the shape design §7.4
 * requires of the client's timer seam.
 */
class AgenticChatWebViewController(
  platformUtils: PlatformUtils,
  currentFileContextProvider: CurrentFileContextProvider,
  insertCodeSnippetService: InsertCodeSnippetService,
  private val client: AgenticChatWebViewClient,
  private val onUiThread: (Runnable) -> Unit,
) : PluginController(ChatWebviewCatalog.AGENTIC_WEBVIEW_ID) {
  constructor(
    platformUtils: PlatformUtils,
    currentFileContextProvider: CurrentFileContextProvider,
    insertCodeSnippetService: InsertCodeSnippetService,
    client: AgenticChatWebViewClient,
  ) : this(
    platformUtils,
    currentFileContextProvider,
    insertCodeSnippetService,
    client,
    { task -> currentDisplay.asyncExec(task) },
  )

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

  /**
   * Takes the connection the notification was sent from, so a late one from a connection that has
   * already been replaced can be told apart from the current one (design §7.1a).
   *
   * The bus dispatches on its own thread, so the client's state is reached through [onUiThread]
   * (design §15).
   */
  @PluginNotification("appReady")
  fun appReady(session: LanguageServerSession) = onUiThread(Runnable { client.markReady(session) })

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
