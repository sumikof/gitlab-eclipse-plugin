package com.gitlab.eclipse.lsp

data class NewPromptRequest(
  val prompt: String,
  val fileContext: FileContext
)

data class FileContext(
  val fileName: String,
  val selectedText: String,
  val contentAboveCursor: String,
  val contentBelowCursor: String
)
