package com.gitlab.eclipse.lsp;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageServer;

public interface GitLabLanguageServer extends LanguageServer {
	@JsonRequest("$/gitlab/webview-metadata")
	public CompletableFuture<List<WebviewInfo>> webviewMetadata();
//	public List<WebviewInfo> webviewMetadata();

//	  @JsonNotification("$/gitlab/didChangeDocumentInActiveEditor")
//	  fun didChangeDocumentInActiveEditor(uri: String)

//	  @JsonNotification("$/gitlab/telemetry")
//	  fun telemetry(params: CodeSuggestionsTelemetryParams)

//	  @JsonRequest("textDocument/inlineCompletion")
//	  fun inlineCompletion(params: InlineCompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>>

}
