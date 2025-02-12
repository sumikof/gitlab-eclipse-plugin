package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.chat.DuoChatStateService
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.capabilities.DidChangeWatchedFileCapability
import com.gitlab.eclipse.lsp.git.GitDiffService
import com.gitlab.eclipse.lsp.messages.GitDiffParams
import com.gitlab.eclipse.lsp.plugins.PluginMessageService
import com.gitlab.eclipse.lsp.plugins.messages.PluginMessage
import com.gitlab.eclipse.lsp.plugins.messages.WebViewMessage
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageRoute
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageType
import com.gitlab.eclipse.utils.logger
import com.google.gson.JsonObject
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@Suppress("UnusedParameter", "TooManyFunctions", "ForbiddenVoid")
class GitLabLanguageServerClient(
  private val codeSuggestionsApiStatusMonitor: CodeSuggestionsApiStatusService = service(),
  private val pluginMessageService: PluginMessageService = service()
) : LanguageClient {
  companion object {
    private const val TIMEOUT_IN_SECONDS = 10L
  }

  private val logger by lazy { logger<GitLabLanguageServerClient>() }

  @JsonRequest("$/gitlab/ai-context/git-diff")
  fun getGitDiff(request: GitDiffParams): CompletableFuture<String?> = CompletableFuture.supplyAsync {
    try {
      val diffProvider = service<GitDiffService>()

      when {
        request.branch != null -> diffProvider.getDiff(request.repositoryUri, request.branch)
        else -> diffProvider.getDiff(request.repositoryUri)
      }
    } catch (e: Throwable) {
      logger.warn("Exception when retrieving git diff.", e)
      null
    }
  }

  @JsonNotification("$/gitlab/featureStateChange")
  fun gitlabFeatureStateChange(
    changes: Array<FeatureStateChange>
  ): CompletableFuture<Void> = CompletableFuture.runAsync {
    changes.forEach { change ->
      when (change.featureId) {
        "chat" -> service<DuoChatStateService>().update(change)
        else -> return@forEach
      }
    }
  }.orTimeout(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS)

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
    pluginMessageService.dispatch(
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
    return pluginMessageService.dispatch(
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
    pluginMessageService.dispatch(
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
    return pluginMessageService.dispatch(
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
  override fun registerCapability(
    params: RegistrationParams
  ): CompletableFuture<Void> = CompletableFuture.runAsync {
    params.registrations.forEach { registration ->
      when {
        registration.method == "workspace/didChangeWatchedFiles" -> {
          service<DidChangeWatchedFileCapability>().register(
            registration.id,
            registration.registerOptions as JsonObject
          )
        }
        else -> logger.warn("[RegisterCapability]: Ignoring unsupported capability ${registration.method}.")
      }
    }
  }

  @Suppress("ForbiddenVoid")
  override fun unregisterCapability(
    params: UnregistrationParams
  ): CompletableFuture<Void> = CompletableFuture.runAsync {
    params.unregisterations.forEach { unregisteration ->
      when {
        unregisteration.method == "workspace/didChangeWatchedFiles" -> {
          service<DidChangeWatchedFileCapability>().unregister(unregisteration.id)
        }
        else -> logger.warn("[UnregisterCapability]: Ignoring unsupported capability ${unregisteration.method}.")
      }
    }
  }
}
