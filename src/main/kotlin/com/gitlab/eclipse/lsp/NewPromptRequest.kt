package com.gitlab.eclipse.lsp

data class NewPromptRequest(
  val prompt: String,
  val fileContext: FileContext? = null
)

data class FileContext(
  val fileName: String,
  val selectedText: String,
  val contentAboveCursor: String,
  val contentBelowCursor: String
)
