package com.gitlab.eclipse.lsp

import org.eclipse.lsp4e.LanguageClientImpl
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest

class GitLabLanguageServerClient : LanguageClientImpl() {
    @JsonNotification("$/gitlab/featureStateChange")
    fun gitlabFeatureStateChange(params: List<FeatureStateChange?>?, _reserved: Any? = null) {
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

    @JsonNotification("\$gitlab/webview/notification")
    fun deprecated__gitlabWebviewNotification(params: Any?) {
        gitlabWebviewNotification(params)
    }

    @JsonRequest("$/gitlab/webview/request")
    fun gitlabWebviewRequest(params: Any?): java.util.concurrent.CompletableFuture<Any> {
        return java.util.concurrent.CompletableFuture.completedFuture<Any>(null)
    }

    @JsonRequest("\$gitlab/webview/request")
    fun deprecated__gitlabWebviewRequest(params: Any?): java.util.concurrent.CompletableFuture<Any> {
        return gitlabWebviewRequest(params)
    }
}
