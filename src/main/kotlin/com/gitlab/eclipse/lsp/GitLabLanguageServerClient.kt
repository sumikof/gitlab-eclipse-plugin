package com.gitlab.eclipse.lsp

import org.eclipse.lsp4e.LanguageClientImpl
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest

@Suppress("UnusedParameter")
class GitLabLanguageServerClient : LanguageClientImpl() {
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

  @JsonNotification("$/gitlab/webview/notification")
  fun gitlabWebviewNotification(params: Any?) {
    return
  }

  @JsonRequest("$/gitlab/webview/request")
  fun gitlabWebviewRequest(params: Any?): java.util.concurrent.CompletableFuture<Any> {
    return java.util.concurrent.CompletableFuture.completedFuture<Any>(null)
  }

  @JsonNotification("\$gitlab/webview/notification")
  fun deprecatedGitlabWebviewNotification(params: Any?) {
    gitlabWebviewNotification(params)
  }

  @JsonRequest("\$gitlab/webview/request")
  fun deprecatedGitlabWebviewRequest(params: Any?): java.util.concurrent.CompletableFuture<Any> {
    return gitlabWebviewRequest(params)
  }
}
