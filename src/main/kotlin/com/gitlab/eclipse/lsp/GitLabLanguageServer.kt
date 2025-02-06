package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.lsp.plugins.messages.ExtensionToPluginNotification
import com.gitlab.eclipse.lsp.webview.ThemeChangedParams
import com.gitlab.eclipse.telemetry.params.TelemetryParams
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageServer

interface GitLabLanguageServer : LanguageServer {
  @JsonRequest("$/gitlab/webview-metadata")
  fun webviewMetadata(): java.util.concurrent.CompletableFuture<List<WebviewInfo?>?>?

  @JsonNotification("$/gitlab/telemetry")
  fun telemetry(params: TelemetryParams)

  @JsonNotification("$/gitlab/plugin/notification")
  fun pluginNotification(notification: ExtensionToPluginNotification)

  @JsonNotification("$/gitlab/theme/didChangeTheme")
  fun didChangeTheme(params: ThemeChangedParams)
}
