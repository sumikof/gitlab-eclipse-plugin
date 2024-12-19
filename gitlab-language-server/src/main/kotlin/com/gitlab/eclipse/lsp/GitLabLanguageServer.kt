package com.gitlab.eclipse.lsp

import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageServer

interface GitLabLanguageServer : LanguageServer {
    @JsonRequest("$/gitlab/webview-metadata")
    fun webviewMetadata(): java.util.concurrent.CompletableFuture<List<WebviewInfo?>?>? //	public List<WebviewInfo> webviewMetadata();

    //	  @JsonNotification("$/gitlab/didChangeDocumentInActiveEditor")
    //	  fun didChangeDocumentInActiveEditor(uri: String)

    //	  @JsonNotification("$/gitlab/telemetry")
    //	  fun telemetry(params: CodeSuggestionsTelemetryParams)

    //	  @JsonRequest("textDocument/inlineCompletion")
    //	  fun inlineCompletion(params: InlineCompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>>
}