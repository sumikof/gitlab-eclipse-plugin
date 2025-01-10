package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.lsp.plugins.PluginCommunicationModule
import com.gitlab.eclipse.lsp.plugins.messages.PluginMessage
import com.gitlab.eclipse.lsp.plugins.messages.WebViewMessage
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageRoute
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageType
import org.eclipse.lsp4e.LanguageClientImpl
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import java.util.concurrent.CompletableFuture

@Suppress("UnusedParameter")
class GitLabLanguageServerClient : LanguageClientImpl() {
  private val pluginCommunicationModule by lazy { PluginCommunicationModule() }

  @JsonNotification("$/gitlab/featureStateChange")
  fun gitlabFeatureStateChange(params: List<FeatureStateChange?>?, reserved: Any? = null) {
    return
  }

  @JsonNotification("$/gitlab/token/check")
  fun gitlabTokenCheck(params: Any?) {
    return
  }

  @JsonNotification("$/gitlab/webview/created")
  fun gitlabWebviewCreated(params: Any?) {
    return
  }

  @JsonNotification("$/gitlab/webview/destroyed")
  fun gitlabWebviewDestroyed(params: Any?) {
    return
  }

  @JsonNotification("$/gitlab/plugin/notification")
  fun gitlabPluginNotification(message: PluginMessage) {
    pluginCommunicationModule.service.dispatch(
      route = PluginMessageRoute(
        method = message.type,
        pluginId = message.pluginId,
        type = PluginMessageType.NOTIFICATION
      ),
      payload = message.payload
    )
  }

  @JsonRequest("$/gitlab/plugin/request")
  fun gitlabPluginRequest(message: PluginMessage): CompletableFuture<Any?> {
    return pluginCommunicationModule.service.dispatch(
      route = PluginMessageRoute(
        method = message.type,
        pluginId = message.pluginId,
        type = PluginMessageType.REQUEST
      ),
      payload = message.payload
    )
  }

  @JsonNotification("$/gitlab/webview/notification")
  fun gitlabWebviewNotification(message: WebViewMessage) {
    pluginCommunicationModule.service.dispatch(
      route = PluginMessageRoute(
        method = message.type,
        pluginId = message.webviewId,
        type = PluginMessageType.NOTIFICATION
      ),
      payload = message.payload
    )
  }

  @JsonRequest("$/gitlab/webview/request")
  fun gitlabWebviewRequest(message: WebViewMessage): CompletableFuture<Any?> {
    return pluginCommunicationModule.service.dispatch(
      route = PluginMessageRoute(
        method = message.type,
        pluginId = message.webviewId,
        type = PluginMessageType.REQUEST
      ),
      payload = message.payload
    )
  }
}
