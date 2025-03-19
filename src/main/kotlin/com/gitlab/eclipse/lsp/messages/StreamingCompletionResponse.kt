package com.gitlab.eclipse.lsp.messages

data class StreamingCompletionResponse(
  val id: String,
  val completion: String = "",
  val done: Boolean
)
