package com.gitlab.eclipse.chat.webview

import com.gitlab.eclipse.chat.context.CurrentFileContextProvider
import com.gitlab.eclipse.chat.services.InsertCodeSnippetService
import com.gitlab.eclipse.lsp.FileContext
import com.gitlab.eclipse.lsp.plugins.PluginController
import com.gitlab.eclipse.lsp.plugins.annotations.PluginNotification
import com.gitlab.eclipse.lsp.plugins.annotations.PluginRequest
import com.gitlab.eclipse.utils.PlatformUtils

class GitLabDuoChatWebViewController(
  platformUtils: PlatformUtils,
  currentFileContextProvider: CurrentFileContextProvider,
  private val gitLabDuoChatWebViewClient: GitLabDuoChatWebViewClient,
  insertCodeSnippetService: InsertCodeSnippetService
) : PluginController(ChatWebviewCatalog.CLASSIC_WEBVIEW_ID) {
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
    gitLabDuoChatWebViewClient.updateFocus(notification.isFocused)
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
