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
import com.gitlab.eclipse.lsp.edit.WorkspaceEditApplier
import com.gitlab.eclipse.lsp.git.GitDiffService
import com.gitlab.eclipse.lsp.messages.CopyTextParams
import com.gitlab.eclipse.lsp.messages.EditorSelectionContext
import com.gitlab.eclipse.lsp.messages.GitDiffParams
import com.gitlab.eclipse.lsp.messages.OpenFileParams
import com.gitlab.eclipse.lsp.messages.StreamingCompletionResponse
import com.gitlab.eclipse.lsp.plugins.PluginMessageService
import com.gitlab.eclipse.lsp.plugins.messages.PluginMessage
import com.gitlab.eclipse.lsp.plugins.messages.WebViewMessage
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageRoute
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageType
import com.gitlab.eclipse.security.ResponseDecision
import com.gitlab.eclipse.security.SecurityScanResponse
import com.gitlab.eclipse.security.SecurityScanStatusReporter
import com.gitlab.eclipse.security.securityScanPathKey
import com.gitlab.eclipse.utils.NotificationUtils
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
   * The identity of the connection this client serves. Bound to this instance, which for the
   * reason given on [connectionEpoch] below means bound to exactly one connection.
   */
  val session: LanguageServerSession = LanguageServerSession()

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

  /**
   * A remote security scan came back.
   *
   * The whole body is contained, and the containment records only the exception's class name: the
   * payload quotes the scanned file and the server's own error text, so neither it nor a failure
   * raised while handling it may reach the error log (design §16.1).
   *
   * The audit line is written before the user is told, and its own failure is contained too — a log
   * that cannot be written must not swallow the notification the user is waiting for (Phase 5A).
   * The notification goes through [NotificationUtils.show], not `showOnUiThread`: this runs on
   * lsp4j's dispatch thread, and building the popup there would be invalid thread access.
   */
  @JsonNotification("$/gitlab/security/remoteSecurityScan/response")
  fun securityScanResponse(response: SecurityScanResponse) {
    runCatching {
      // Nothing identifies the request, so an answer with no file cannot be matched to one.
      val filePath = response.filePath ?: return
      val path = securityScanPathKey(filePath)
      when (val decision = SecurityScanStatusReporter.settle(path, response, connectionEpoch)) {
        is ResponseDecision.Rejected -> Unit
        is ResponseDecision.Report -> {
          runCatching { logger.info(decision.auditLine) }
          decision.notify?.let { NotificationUtils.show(it) }
        }
      }
    }.onFailure { failure ->
      // Contained like the audit line above it: this is the outermost handler on lsp4j's dispatch
      // thread, so a log that throws here would escape into the dispatch loop itself (§16.2).
      runCatching {
        logger.warn("Failed to handle a security scan response: ${failure::class.simpleName}")
      }
    }
  }

  /**
   * The server asks for a file to be opened — `$/gitlab/openFile`.
   *
   * [WorkspaceFileOpener.open] is called with no display hop of any kind around it: the opener
   * reaches the UI thread for itself with `asyncExec`, and a `syncExec` here would deadlock the
   * dispatch thread against a UI thread that is waiting on a server response. What it does get is
   * `runAsync`, like the other notification handlers in this file — resolution probes the
   * filesystem, and a stalled mount must not stop lsp4j reading the next message. Nothing is
   * logged here: the opener owns what may be said about a path, which is nothing.
   */
  @JsonNotification("$/gitlab/openFile")
  fun gitlabOpenFile(
    params: OpenFileParams
  ): CompletableFuture<Void> = CompletableFuture.runAsync {
    service<WorkspaceFileOpener>().open(params)
  }.orTimeout(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS)

  /**
   * The server asks for text to be copied — `$/gitlab/copyText`.
   *
   * [CopyTextHandler.handle] returns as soon as the write is queued on the UI thread, and the
   * future it queues is deliberately not awaited: a runnable dropped at workbench teardown would
   * then hang this handler instead of simply never copying. Fire and return.
   */
  @JsonNotification("$/gitlab/copyText")
  fun gitlabCopyText(
    params: CopyTextParams
  ): CompletableFuture<Void> = CompletableFuture.runAsync {
    service<CopyTextHandler>().handle(params)
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
      payload = message.payload,
      session = session
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
      payload = message.payload,
      session = session
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
      payload = message.payload,
      session = session
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
      payload = message.payload,
      session = session
    )
  }

  /**
   * The server asks the client to apply an edit — `workspace/applyEdit`.
   *
   * The applier's own future is returned **unchanged**, and that is the whole contract of this
   * handler. Exactly one party may answer the request: the UI runnable that applied (or did not
   * apply) the edit, the applier's internal start timeout, or a setup failure. An `orTimeout` or a
   * `thenApply` bolted on here would complete the future from outside that state machine, so the
   * server could be told `applied:false` — and then write the file itself — while a queued UI
   * runnable still goes on to apply the same edits. The file gets them twice.
   *
   * Deliberately **not** annotated, unlike the handlers this client declares itself:
   * [LanguageClient.applyEdit] already carries `@JsonRequest("workspace/applyEdit")`, and lsp4j
   * collects annotated methods from the class *and* every interface it implements, rejecting any
   * method name it meets twice (`GenericEndpoint` throws `IllegalStateException: Multiple methods
   * for name workspace/applyEdit`). Repeating the annotation would take every handler in this
   * class down with it, because the endpoint is built for the whole client at once.
   */
  override fun applyEdit(
    params: ApplyWorkspaceEditParams
  ): CompletableFuture<ApplyWorkspaceEditResponse> = service<WorkspaceEditApplier>().applyEdit(params)

  /**
   * The server asks for a URI to be shown — `window/showDocument`. It is opened in the external
   * browser; see [ShowDocumentLauncher] for why a file URI never gets that far.
   *
   * A timeout here is safe, unlike on [applyEdit]: nothing is edited, so the worst a late answer
   * costs is one line in the server's log. The server's schema for this response is `z.void()`
   * while lsp4j always serialises `"result": null`, so it logs an info line whatever we send; the
   * URI is open by then and the server contains the mismatch. Not a defect to work around here.
   *
   * Not annotated, for the reason given on [applyEdit].
   */
  override fun showDocument(
    params: ShowDocumentParams
  ): CompletableFuture<ShowDocumentResult> = service<ShowDocumentLauncher>().show(params.uri)
    .thenApply { launched -> ShowDocumentResult(launched) }
    .orTimeout(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS)

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
