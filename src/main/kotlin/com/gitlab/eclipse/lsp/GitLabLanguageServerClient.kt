package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.authentication.AuthenticationStateService
import com.gitlab.eclipse.chat.ChatAvailabilityService
import com.gitlab.eclipse.chat.DuoChatStateService
import com.gitlab.eclipse.chat.context.EditorSelectionContextProvider
import com.gitlab.eclipse.codesuggestions.StreamingCodeSuggestionsManager
import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.capabilities.DidChangeWatchedFileCapability
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticGenerationRegistry
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticMarkerService
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticUri
import com.gitlab.eclipse.lsp.git.GitDiffService
import com.gitlab.eclipse.lsp.messages.EditorSelectionContext
import com.gitlab.eclipse.lsp.messages.GitDiffParams
import com.gitlab.eclipse.lsp.messages.StreamingCompletionResponse
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
  private val pluginMessageService: PluginMessageService = service()
) : LanguageClient {
  companion object {
    private const val TIMEOUT_IN_SECONDS = 10L
  }

  private val logger by lazy { logger<GitLabLanguageServerClient>() }

  /**
   * Connection epoch captured when this client is constructed. A new client is built for every
   * language server start, and `restart()` stops before it starts, so a new client always sees a
   * newer epoch than the one it replaces. Every diagnostics callback checks the captured value so
   * that late notifications from a dead connection cannot publish markers.
   */
  private val connectionEpoch: Long = DiagnosticGenerationRegistry.currentEpoch

  @JsonNotification("streamingCompletionResponse")
  fun streamingCompletionResponse(
    params: StreamingCompletionResponse
  ): CompletableFuture<Void> = CompletableFuture.runAsync {
    service<StreamingCodeSuggestionsManager>().receive(params)
  }.orTimeout(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS)

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

  @JsonRequest("$/gitlab/ai-context/editor-selection")
  fun getEditorSelection(): CompletableFuture<EditorSelectionContext?> =
    service<EditorSelectionContextProvider>().provide()

  @JsonNotification("$/gitlab/featureStateChange")
  fun gitlabFeatureStateChange(
    changes: Array<FeatureStateChange>
  ): CompletableFuture<Void> = CompletableFuture.runAsync {
    changes.forEach { change ->
      when (change.featureId) {
        "authentication" -> service<AuthenticationStateService>().update(change)
        "chat" -> {
          service<DuoChatStateService>().update(change)
          service<ChatAvailabilityService>().updateClassic(change)
        }

        "agentic_chat" -> service<ChatAvailabilityService>().updateAgentic(change)
        "code_suggestions" -> service<CodeSuggestionsStateService>().update(change)
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

  override fun telemetryEvent(event: Any) {
    logger.info("telemetryEvent: $event")
  }

  override fun publishDiagnostics(diagnostic: PublishDiagnosticsParams) {
    // Diagnostics carry scan findings and absolute file locations, so neither the payload nor the
    // failure message may reach the error log.
    runCatching { applyDiagnostics(diagnostic) }
      .onFailure { logger.warn("Failed to handle publishDiagnostics: ${it::class.simpleName}") }
  }

  private fun applyDiagnostics(params: PublishDiagnosticsParams) {
    val key = DiagnosticUri.normalize(params.uri) ?: return
    val incoming = params.diagnostics ?: emptyList()

    // Only suspended sources are dropped; diagnostics from every other source still pass through.
    val tokens = incoming.associateWith {
      DiagnosticGenerationRegistry.acceptToken(it.source, connectionEpoch)
    }
    val accepted = incoming.filter { tokens[it] != null }

    // A non-empty batch that was fully dropped is a no-op: an empty set must not gain the authority
    // to replace everything the file currently shows.
    if (incoming.isNotEmpty() && accepted.isEmpty()) return

    val generation = DiagnosticGenerationRegistry.nextGeneration(key, connectionEpoch) ?: return

    // Re-check for sources that were suspended while this batch was being prepared.
    val finalDiagnostics = accepted.filter { DiagnosticGenerationRegistry.isTokenValid(tokens[it]) }
    if (accepted.isNotEmpty() && finalDiagnostics.isEmpty()) return

    service<DiagnosticMarkerService>().apply(key, finalDiagnostics, generation, connectionEpoch)
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
