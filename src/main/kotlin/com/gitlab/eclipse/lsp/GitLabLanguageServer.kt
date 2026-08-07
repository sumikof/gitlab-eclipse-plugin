package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationParams
import com.gitlab.eclipse.lsp.messages.InlineCompletionParams
import com.gitlab.eclipse.lsp.messages.StreamWithId
import com.gitlab.eclipse.lsp.plugins.messages.ExtensionToPluginNotification
import com.gitlab.eclipse.lsp.webview.ThemeChangedParams
import com.gitlab.eclipse.preferences.healthcheck.FeatureStateParams
import com.gitlab.eclipse.security.SecurityScanParams
import com.gitlab.eclipse.telemetry.params.TelemetryParams
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import java.util.concurrent.CompletableFuture

/**
 * Custom remote interface for the GitLab Language Server.
 *
 * This interface intentionally does NOT extend [org.eclipse.lsp4j.services.LanguageServer]
 * to avoid a "Duplicate RPC method textDocument/inlineCompletion" conflict on Eclipse 2026-03+.
 * LSP4J 1.0.0 added a native `textDocument/inlineCompletion` method to `TextDocumentService`,
 * which is discovered via the `@JsonDelegate` on `LanguageServer.getTextDocumentService()`.
 * Declaring the same method here would cause `ServiceEndpoints` to find it twice.
 *
 * Instead, we declare the standard LSP lifecycle methods we need directly.
 */
@Suppress("TooManyFunctions")
interface GitLabLanguageServer {
  @JsonRequest
  fun initialize(params: InitializeParams): CompletableFuture<InitializeResult>

  @JsonNotification
  fun initialized(params: InitializedParams?)

  @JsonRequest
  fun shutdown(): CompletableFuture<Any?>

  @JsonNotification
  fun exit()

  @JsonNotification("textDocument/didOpen")
  fun didOpen(params: DidOpenTextDocumentParams)

  @JsonNotification("textDocument/didClose")
  fun didClose(params: DidCloseTextDocumentParams)

  @JsonNotification("textDocument/didChange")
  fun didChange(params: DidChangeTextDocumentParams)

  @JsonNotification("workspace/didChangeConfiguration")
  fun didChangeConfiguration(params: DidChangeConfigurationParams)

  @JsonNotification("workspace/didChangeWatchedFiles")
  fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams)

  @JsonNotification("$/gitlab/didChangeDocumentInActiveEditor")
  fun didChangeDocumentInActiveEditor(uri: String)

  @JsonRequest("$/gitlab/webview-metadata")
  fun webviewMetadata(): java.util.concurrent.CompletableFuture<List<WebviewInfo?>?>?

  @JsonNotification("$/gitlab/telemetry")
  fun telemetry(params: TelemetryParams)

  @JsonNotification("$/gitlab/plugin/notification")
  fun pluginNotification(notification: ExtensionToPluginNotification)

  @JsonNotification("$/gitlab/theme/didChangeTheme")
  fun didChangeTheme(params: ThemeChangedParams)

  @JsonRequest("textDocument/inlineCompletion")
  fun inlineCompletion(params: InlineCompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>>

  @JsonNotification("cancelStreaming")
  fun cancelStreaming(params: StreamWithId)

  @JsonNotification("$/gitlab/security/remoteSecurityScan")
  fun runSecurityScan(params: SecurityScanParams)

  @JsonRequest("$/gitlab/validateConfiguration")
  fun validateConfiguration(
    params: GitLabLanguageServerConfigurationParams
  ): CompletableFuture<List<FeatureStateParams>>
}
