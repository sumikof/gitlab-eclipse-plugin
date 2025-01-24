package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.plugins.PluginCommunicationModule
import com.gitlab.eclipse.lsp.plugins.messages.PluginMessage
import com.gitlab.eclipse.lsp.plugins.messages.WebViewMessage
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageRoute
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageType
import com.gitlab.eclipse.utils.logger
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageClient
import java.util.concurrent.CompletableFuture

@Suppress("UnusedParameter", "TooManyFunctions")
class GitLabLanguageServerClient(
  private val codeSuggestionsApiStatusMonitor: CodeSuggestionsApiStatusService = service()
) : LanguageClient {
  private val logger by lazy { logger<GitLabLanguageServerClient>() }
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

  @JsonNotification("$/gitlab/api/error")
  fun gitLabApiError() {
    codeSuggestionsApiStatusMonitor.reportError()
  }

  @JsonNotification("$/gitlab/api/recovery")
  fun gitLabApiRecovery() {
    codeSuggestionsApiStatusMonitor.reportRecovery()
  }

  override fun telemetryEvent(event: Any) {
    logger.info("telemetryEvent: $event")
  }

  override fun publishDiagnostics(diagnostic: PublishDiagnosticsParams) {
    logger.info("publishDiagnostics: $diagnostic")
  }

  override fun showMessage(message: MessageParams) {
    logger.info("showMessage: $message")
  }

  override fun showMessageRequest(message: ShowMessageRequestParams): CompletableFuture<MessageActionItem> {
    logger.info("showMessageRequest: $message")
    return CompletableFuture.completedFuture(MessageActionItem())
  }

  override fun logMessage(message: MessageParams) {
    logger.info("logMessage: $message")
  }

  @Suppress("ForbiddenVoid")
  override fun registerCapability(params: RegistrationParams?): CompletableFuture<Void> {
    logger.info("registerCapability: $params")
    return CompletableFuture()
  }

  @Suppress("ForbiddenVoid")
  override fun unregisterCapability(params: UnregistrationParams?): CompletableFuture<Void> {
    logger.info("unregisterCapability: $params")
    return CompletableFuture()
  }
}
