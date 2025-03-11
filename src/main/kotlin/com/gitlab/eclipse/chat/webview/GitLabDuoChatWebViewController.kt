package com.gitlab.eclipse.chat.webview

import com.gitlab.eclipse.chat.context.CurrentFileContextProvider
import com.gitlab.eclipse.chat.services.InsertCodeSnippetService
import com.gitlab.eclipse.lsp.FileContext
import com.gitlab.eclipse.lsp.plugins.PluginController
import com.gitlab.eclipse.lsp.plugins.annotations.PluginNotification
import com.gitlab.eclipse.lsp.plugins.annotations.PluginRequest
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import org.eclipse.swt.dnd.Clipboard
import org.eclipse.swt.dnd.TextTransfer
import java.net.URI

class GitLabDuoChatWebViewController(
  private val platformUtils: PlatformUtils,
  private val currentFileContextProvider: CurrentFileContextProvider,
  private val gitLabDuoChatWebViewClient: GitLabDuoChatWebViewClient,
  private val insertCodeSnippetService: InsertCodeSnippetService
) : PluginController("duo-chat-v2") {
  private val logger by lazy { logger<GitLabDuoChatWebViewController>() }

  @PluginRequest("getCurrentFileContext")
  fun getCurrentFileContext(): FileContext? {
    val textEditor = platformUtils.getActiveTextEditor()
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

  @PluginNotification("appReady")
  fun appReady() = Unit

  @PluginNotification("insertCodeSnippet")
  fun insertCodeSnippet(notification: InsertCodeSnippetNotification) {
    insertCodeSnippetService.insertCodeSnippet(notification.snippet)
  }

  @PluginNotification("focusChange")
  fun focusChange(notification: FocusChangeNotification) {
    gitLabDuoChatWebViewClient.updateFocus(notification.isFocused)
  }

  @PluginNotification("openLink")
  fun openLink(notification: OpenLinkNotification) {
    platformUtils.getWorkbench().browserSupport.externalBrowser.openURL(
      URI.create(notification.href).toURL()
    )
  }

  @PluginNotification("copyCodeSnippet")
  fun copyCodeSnippet(notification: CopyCodeSnippetNotification) {
    currentDisplay.syncExec {
      val clipboard = Clipboard(currentDisplay)
      clipboard.setContents(arrayOf(notification.snippet), arrayOf(TextTransfer.getInstance()))
      clipboard.dispose()
    }
  }
}
