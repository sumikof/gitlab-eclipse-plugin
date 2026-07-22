package com.gitlab.eclipse.lsp.messages

/**
 * Response payload for the language server's `$/gitlab/ai-context/editor-selection` request.
 *
 * The language server only reads `selectedText` and `fileName`; whole-file content is
 * deliberately not included.
 */
data class EditorSelectionContext(
  val fileName: String,
  val selectedText: String
)
