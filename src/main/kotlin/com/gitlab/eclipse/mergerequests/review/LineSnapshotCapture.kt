package com.gitlab.eclipse.mergerequests.review

import org.eclipse.core.runtime.CoreException
import org.eclipse.jface.text.ITextSelection
import org.eclipse.jface.text.source.IVerticalRulerInfo
import org.eclipse.ui.IEditorInput
import org.eclipse.ui.IFileEditorInput
import org.eclipse.ui.IURIEditorInput
import org.eclipse.ui.editors.text.IEncodingSupport
import org.eclipse.ui.texteditor.ITextEditor
import java.io.File
import java.nio.charset.Charset

/**
 * Which menu the line command came from (design §9.3 [UI turn 1]): the text menu's caret, or the
 * ruler's last-clicked line (E2).
 */
enum class LineSource { CARET, RULER }

/**
 * What the menu-time UI turn read off the editor, before any decision: plain values, so every
 * decision on them is SWT-free ([LineSnapshotCapture.capture]).
 *
 * [filePath] is `null` when the input is not a local file (G2); [zeroBasedLine] is the
 * document's 0-based line, `-1` when unknown (outside the ruler, E2, or no text selection);
 * [charsetName] is the editor's encoding name, `null` when it could not be read.
 */
data class EditorLineFacts(
  val filePath: File?,
  val dirty: Boolean,
  val zeroBasedLine: Int,
  val documentText: String,
  val numberOfLines: Int,
  val charsetName: String?,
)

/** The outcome of the menu-time capture: a frozen [LineSnapshot], or a fixed refusal text to notify. */
sealed interface CaptureResult {
  data class Captured(val snapshot: LineSnapshot) : CaptureResult

  data class Refused(val message: String) : CaptureResult
}

/**
 * The menu-time capture of "Add Merge Request Comment on This Line…" (design §9.3 [UI turn 1],
 * §9.3.2): the gates G2 (a local file) and G3 (not dirty), then the frozen [LineSnapshot]. G1 (the
 * active part is an [ITextEditor]) is the handler's, since nothing can be read without one.
 *
 * [capture] and its helpers are pure; [readFacts] is the only part that touches the editor and
 * runs on the UI thread, in the same turn as the menu.
 */
object LineSnapshotCapture {
  const val NOT_TEXT_EDITOR_MESSAGE = "Merge request line comments are only available in a text editor."
  const val NOT_LOCAL_FILE_MESSAGE =
    "Merge request line comments are only available for a local file of the working tree."
  const val NO_LINE_MESSAGE = "Could not tell which line to use; click the line and try again."
  const val NO_CHARSET_MESSAGE = "Could not determine this file's encoding, so its lines cannot be commented on."

  /** G2 → G3 → the line → the charset; the first failing one is the refusal. */
  fun capture(facts: EditorLineFacts): CaptureResult {
    val filePath = facts.filePath ?: return CaptureResult.Refused(NOT_LOCAL_FILE_MESSAGE)
    if (facts.dirty) return CaptureResult.Refused(LineCommentAttempt.SAVE_FIRST_MESSAGE)
    val oneBasedLine = oneBasedLineOf(facts.zeroBasedLine) ?: return CaptureResult.Refused(NO_LINE_MESSAGE)
    val charset = charsetOf(facts.charsetName) ?: return CaptureResult.Refused(NO_CHARSET_MESSAGE)
    val lineCount = lineCountOf(facts.documentText.length, facts.numberOfLines)
    return CaptureResult.Captured(LineSnapshot(filePath, oneBasedLine, lineCount, facts.documentText, charset))
  }

  /** A 0-based document line as the 1-based line everything downstream uses; `null` for an unknown (negative) line. */
  fun oneBasedLineOf(zeroBasedLine: Int): Int? = if (zeroBasedLine < 0) null else zeroBasedLine + 1

  /** An empty document has no line to comment on, although `IDocument.getNumberOfLines()` reports 1 for it. */
  fun lineCountOf(documentLength: Int, numberOfLines: Int): Int = if (documentLength == 0) 0 else numberOfLines

  /** The charset for an encoding name; `null` for a missing, blank, illegal or unsupported name. */
  fun charsetOf(name: String?): Charset? {
    if (name.isNullOrBlank()) return null
    return try {
      Charset.forName(name)
    } catch (ignored: IllegalArgumentException) {
      // IllegalCharsetNameException and UnsupportedCharsetException are both IllegalArgumentExceptions.
      null
    }
  }

  /**
   * UI thread. Reads [editor]'s facts for [source]: the caret line (`ITextSelection.startLine`) or
   * the ruler's last-clicked line (E2, `-1` outside), the document text and line count, the dirty
   * flag, the local file and its encoding (`IFile.getCharset()` for a workspace file, the editor's
   * `IEncodingSupport` for an external one). An editor without a document reads as "not a local file".
   */
  fun readFacts(editor: ITextEditor, source: LineSource): EditorLineFacts {
    val input = editor.editorInput
    val document = editor.documentProvider?.getDocument(input)
    val zeroBasedLine = when (source) {
      LineSource.CARET -> (editor.selectionProvider?.selection as? ITextSelection)?.startLine ?: -1
      LineSource.RULER -> editor.getAdapter(IVerticalRulerInfo::class.java)?.lineOfLastMouseButtonActivity ?: -1
    }
    return EditorLineFacts(
      filePath = if (document == null) null else localFileOf(input),
      dirty = editor.isDirty,
      zeroBasedLine = zeroBasedLine,
      documentText = document?.get().orEmpty(),
      numberOfLines = document?.numberOfLines ?: 0,
      charsetName = charsetNameOf(editor, input),
    )
  }

  /** G2: a workspace file with a local location, or a `file:` URI; otherwise `null`. */
  private fun localFileOf(input: IEditorInput?): File? = when (input) {
    is IFileEditorInput -> input.file?.location?.toFile()?.absoluteFile
    is IURIEditorInput -> input.uri?.takeIf { it.scheme.equals("file", ignoreCase = true) }?.let { uri ->
      try {
        File(uri).absoluteFile
      } catch (ignored: IllegalArgumentException) {
        null // a file: URI that names no plain path (authority, query, fragment)
      }
    }
    else -> null
  }

  private fun charsetNameOf(editor: ITextEditor, input: IEditorInput?): String? = when (input) {
    is IFileEditorInput -> try {
      input.file?.charset
    } catch (ignored: CoreException) {
      null
    }
    else -> editor.getAdapter(IEncodingSupport::class.java)?.encoding
  }
}
