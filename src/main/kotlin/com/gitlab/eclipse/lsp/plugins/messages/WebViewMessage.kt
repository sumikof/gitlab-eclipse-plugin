package com.gitlab.eclipse.lsp.plugins.messages

data class WebViewMessage(
  val webviewId: String,
  val type: String,
  val payload: Any?
)
