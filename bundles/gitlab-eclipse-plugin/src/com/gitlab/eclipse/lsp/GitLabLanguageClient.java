package com.gitlab.eclipse.lsp;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4e.LanguageClientImpl;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;

import com.gitlab.eclipse.suggestions.StreamingCompletionEvents;

// NOTE: For some reason Eclipse tries to cast to this non-API default implementation.
@SuppressWarnings("restriction")
public class GitLabLanguageClient extends LanguageClientImpl {
	@JsonNotification("streamingCompletionResponse")
	public void streamingCompletionResponse(StreamingCompletionResponse params) {
		StreamingCompletionEvents.publish(params);
	}

	@JsonNotification("$/gitlab/featureStateChange")
	public void gitlabFeatureStateChange(List<FeatureStateChange> params, Object... _reserved) {
		return;
	}

	@JsonNotification("$/gitlab/token/check")
	public void gitlabTokenCheck(Object params) {
		return;
	}

	@JsonNotification("$/gitlab/webview/created")
	public void gitlabWebviewCreated(Object params) {
		return;
	}

	@JsonNotification("$/gitlab/webview/destroyed")
	public void gitlabWebviewDestroyed(Object params) {
		return;
	}

	@JsonNotification("$/gitlab/webview/notification")
	public void gitlabWebviewNotification(Object params) {
		return;
	}

	@JsonNotification("$gitlab/webview/notification")
	public void deprecated__gitlabWebviewNotification(Object params) {
		gitlabWebviewNotification(params);
	}

	@JsonRequest("$/gitlab/webview/request")
	public CompletableFuture<Object> gitlabWebviewRequest(Object params) {
		return CompletableFuture.completedFuture(null);
	}

	@JsonRequest("$gitlab/webview/request")
	public CompletableFuture<Object> deprecated__gitlabWebviewRequest(Object params) {
		return gitlabWebviewRequest(params);
	}
}
