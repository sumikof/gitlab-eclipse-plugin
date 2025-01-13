package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.lsp.plugins.messages.ExtensionToPluginNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageServer

interface GitLabLanguageServer : LanguageServer {
  @JsonRequest("$/gitlab/webview-metadata")
  fun webviewMetadata(): java.util.concurrent.CompletableFuture<List<WebviewInfo?>?>?

  @JsonNotification("$/gitlab/plugin/notification")
  fun pluginNotification(notification: ExtensionToPluginNotification)
}
