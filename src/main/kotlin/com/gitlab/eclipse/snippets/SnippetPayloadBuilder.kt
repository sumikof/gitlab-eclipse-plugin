package com.gitlab.eclipse.snippets

/** Visibility levels the snippet API accepts. [wireValue] is what goes on the wire. */
enum class SnippetVisibility(val wireValue: String) {
  PRIVATE("private"),
  PUBLIC("public"),
}

/** Zero-based, inclusive line range of the editor selection. */
data class SelectionRange(val startLine: Int, val endLine: Int)

/** Exactly the body `POST /projects/{id}/snippets` takes. */
data class SnippetPayload(
  val title: String,
  val fileName: String,
  val visibility: String,
  val content: String,
  /** Only patch snippets carry one; omitted from the request body when null (design F3). */
  val description: String? = null,
)

/**
 * Builds the snippet request body.
 *
 * SWT-free on purpose: the handler reads the editor on the UI thread and hands the plain values
 * in, which is what makes the line-rounding rule below unit-testable.
 */
object SnippetPayloadBuilder {
  fun build(
    fileName: String,
    fullText: String,
    selection: SelectionRange?,
    visibility: SnippetVisibility,
  ): SnippetPayload {
    val content = if (selection == null) fullText else sliceLines(fullText, selection)
    return SnippetPayload(
      title = fileName,
      fileName = fileName,
      visibility = visibility.wireValue,
      content = content,
    )
  }

  /**
   * Whole-line slice matching VSCode's `Range(Position(start.line, 0), Position(end.line + 1, 0))`
   * (`create_snippet.ts`): from the start of the first selected line to the start of the line AFTER
   * the last selected one, so a partial selection still yields complete lines. The trailing newline
   * is only added when that following line exists in the source — otherwise the slice would gain a
   * line break the document does not have.
   */
  private fun sliceLines(text: String, selection: SelectionRange): String {
    val lines = text.split("\n")
    val from = selection.startLine.coerceIn(0, lines.lastIndex)
    val to = selection.endLine.coerceIn(from, lines.lastIndex)
    val body = lines.subList(from, to + 1).joinToString("\n")
    return if (to < lines.lastIndex) "$body\n" else body
  }
}
