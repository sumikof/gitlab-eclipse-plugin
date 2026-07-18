package com.gitlab.eclipse.lsp;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageServer;

public interface GitLabLanguageServer extends LanguageServer {
	@JsonRequest("$/gitlab/webview-metadata")
	public CompletableFuture<List<WebviewInfo>> webviewMetadata();

	@JsonRequest("textDocument/inlineCompletion")
	public CompletableFuture<InlineCompletionList> inlineCompletion(InlineCompletionParams params);

	@JsonNotification("cancelStreaming")
	public void cancelStreaming(CancelStreamingParams params);

	@JsonNotification("$/gitlab/telemetry")
	public void telemetry(TelemetryParams params);

	@JsonNotification("$/gitlab/didChangeDocumentInActiveEditor")
	public void didChangeDocumentInActiveEditor(String uri);
}
