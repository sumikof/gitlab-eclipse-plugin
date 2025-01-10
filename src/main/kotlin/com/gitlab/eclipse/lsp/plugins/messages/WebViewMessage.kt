package com.gitlab.eclipse.lsp.plugins.messages

import com.google.gson.JsonElement

data class WebViewMessage(
  val webviewId: String,
  val type: String,
  val payload: JsonElement?
)
