package com.gitlab.eclipse.lsp.edit

import org.eclipse.jface.text.BadLocationException
import org.eclipse.jface.text.IDocument
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextEdit
import org.eclipse.text.edits.ReplaceEdit

/**
 * Converts LSP text edits to Eclipse [ReplaceEdit]s. Pure logic: no SWT, no workbench,
 * no file buffers.
 */
object LspTextEditConverter {
  /**
   * Converts LSP text edits to ReplaceEdits against [document].
   *
   * All Position -> offset conversion, and therefore all range checking, happens here:
   * by the time the caller applies the returned edits the offsets are known to be inside
   * the document.
   *
   * @throws BadLocationException if any range falls outside [document]
   */
  fun toReplaceEdits(document: IDocument, edits: List<TextEdit>): List<ReplaceEdit> =
    edits.map { edit ->
      val startOffset = toOffset(document, edit.range.start)
      val endOffset = toOffset(document, edit.range.end)
      ReplaceEdit(startOffset, endOffset - startOffset, edit.newText)
    }

  private fun toOffset(document: IDocument, position: Position): Int {
    val lineOffset = document.getLineOffset(position.line)
    val lineLengthWithDelimiter = document.getLineLength(position.line)
    val delimiterLength = document.getLineDelimiter(position.line)?.length ?: 0
    val lineContentLength = lineLengthWithDelimiter - delimiterLength
    if (position.character > lineContentLength) {
      throw BadLocationException(
        "character ${position.character} is beyond the end of line ${position.line}",
      )
    }
    return lineOffset + position.character
  }
}
