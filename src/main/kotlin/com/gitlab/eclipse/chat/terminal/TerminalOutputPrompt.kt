package com.gitlab.eclipse.chat.terminal

import com.gitlab.eclipse.lsp.FileContext
import com.gitlab.eclipse.lsp.NewPromptRequest

/**
 * Builds the classic chat's `explainTerminalOutput` prompt.
 *
 * The terminal text travels in the prompt's own `fileContext` rather than in the language server's
 * shared AI context (`$/gitlab/ai-context/add`). That store is shared by every classic prompt and
 * cleared by whichever is processed next, and nothing tells the client when the webview has consumed
 * an item, so an item added there can be lost to, or leak into, another prompt. A `fileContext` is
 * bound to its one prompt: the classic webview sends it as that prompt's `currentFile`.
 *
 * The context is plain data. Neither the webview nor the server reads a file for it, so no file is
 * written: [CONTEXT_NAME] is only a label.
 */
object TerminalOutputPrompt {

  const val PROMPT_TYPE = "explainTerminalOutput"

  /** Shown as the context's file name; not a path. */
  const val CONTEXT_NAME = "Terminal output"

  /**
   * UTF-16 code units, matching the classic webview's `MAX_CONTENT_LENGTH`. The webview trims only
   * the text around the cursor and never the selected text, so the limit is applied here.
   */
  const val MAX_LENGTH = 400_000

  data class Built(val request: NewPromptRequest, val truncated: Boolean)

  fun build(text: String): Built {
    val kept = tail(text)
    val context = FileContext(
      fileName = CONTEXT_NAME,
      selectedText = kept,
      contentAboveCursor = "",
      contentBelowCursor = "",
    )
    return Built(NewPromptRequest(prompt = PROMPT_TYPE, fileContext = context), truncated = kept.length != text.length)
  }

  /** The last [MAX_LENGTH] code units — the latest output — without a leading lone low surrogate. */
  private fun tail(text: String): String {
    if (text.length <= MAX_LENGTH) return text
    var start = text.length - MAX_LENGTH
    if (Character.isLowSurrogate(text[start])) start += 1
    return text.substring(start)
  }
}
