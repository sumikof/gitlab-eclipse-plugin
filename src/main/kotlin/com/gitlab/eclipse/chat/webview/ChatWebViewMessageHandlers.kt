package com.gitlab.eclipse.chat.webview

import com.gitlab.eclipse.chat.context.CurrentFileContextProvider
import com.gitlab.eclipse.chat.services.InsertCodeSnippetService
import com.gitlab.eclipse.lsp.FileContext
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import org.eclipse.swt.dnd.Clipboard
import org.eclipse.swt.dnd.TextTransfer
import java.net.URI

/**
 * Webview-to-host message handlers shared by the classic Duo Chat (`duo-chat-v2`) and the
 * Agentic Duo Chat (`agentic-duo-chat`) webviews. Mirrors `registerDuoChatHandlers` in
 * gitlab-workflow (`src/common/webview/duo_chat/duo_chat_handlers.ts`), which registers the
 * identical handler set for both webview ids.
 *
 * [com.gitlab.eclipse.lsp.plugins.PluginRegistry] discovers routes via `declaredMethods`,
 * which does not include inherited methods, so the controllers cannot share annotated methods
 * through a base class. Instead each controller declares its own annotated methods and
 * delegates to an instance of this class.
 */
class ChatWebViewMessageHandlers(
  private val platformUtils: PlatformUtils,
  private val currentFileContextProvider: CurrentFileContextProvider,
  private val insertCodeSnippetService: InsertCodeSnippetService
) {
  private val logger by lazy { logger<ChatWebViewMessageHandlers>() }

  fun getCurrentFileContext(): FileContext? =
    currentDisplay.syncCall<FileContext?, Exception> {
      val textEditor = platformUtils.getActiveTextEditor()
        ?: return@syncCall null

      currentFileContextProvider.provide(textEditor)
    }

  fun showMessage(notification: ShowMessageNotification) {
    when (val type = notification.type) {
      "error" -> logger.error(notification.message)
      "warning" -> logger.warn(notification.message)
      "info" -> logger.warn(notification.message)
      else -> logger.warn("Received unknown message type ($type) with content: ${notification.message}")
    }
  }

  fun insertCodeSnippet(notification: InsertCodeSnippetNotification) {
    insertCodeSnippetService.insertCodeSnippet(notification.snippet)
  }

  /**
   * Classic chat sends `openLink` with `href`; Agentic chat sends `openUrl` with `url`.
   * Both are routed here, coalescing like the VSCode handler (`href || url`).
   */
  fun openLink(notification: OpenLinkNotification) {
    val link = notification.href ?: notification.url ?: return

    platformUtils.getWorkbench().browserSupport.externalBrowser.openURL(
      URI.create(link).toURL()
    )
  }

  fun copyCodeSnippet(notification: CopyCodeSnippetNotification) {
    copyToClipboard(notification.snippet)
  }

  fun copyMessage(notification: CopyMessageNotification) {
    copyToClipboard(notification.message)
  }

  private fun copyToClipboard(content: String) {
    currentDisplay.syncExec {
      val clipboard = Clipboard(currentDisplay)
      clipboard.setContents(arrayOf(content), arrayOf(TextTransfer.getInstance()))
      clipboard.dispose()
    }
  }
}
