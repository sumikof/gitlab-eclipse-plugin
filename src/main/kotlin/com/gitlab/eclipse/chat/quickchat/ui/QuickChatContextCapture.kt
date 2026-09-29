package com.gitlab.eclipse.chat.quickchat.ui

import com.gitlab.eclipse.chat.quickchat.CapturedContext
import com.gitlab.eclipse.chat.quickchat.QuickChatContextBuilder
import com.gitlab.eclipse.chat.quickchat.TextWindow
import org.eclipse.core.resources.IFile
import org.eclipse.jface.text.BadLocationException
import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.ITextSelection
import org.eclipse.ui.IEditorInput
import org.eclipse.ui.IURIEditorInput
import org.eclipse.ui.editors.text.ILocationProvider
import org.eclipse.ui.texteditor.ITextEditor
import java.io.File
import java.net.URI

/**
 * Fixes one question's context from **the popup's editor**, never the active one (design §9.2.1):
 * its document, its current selection, the name sent for its file and the file's location for the
 * preflight. UI thread only; reads only the windows [QuickChatContextBuilder] asks for.
 */
fun captureContext(editor: ITextEditor, question: String): CapturedContext {
  val input = editor.editorInput
  val document = input?.let { editor.documentProvider?.getDocument(it) }
  val selection = editor.selectionProvider?.selection as? ITextSelection
  val file = input?.getAdapter(IFile::class.java)
  val anchorFile = input?.let { anchorFileOf(file?.location?.toFile(), providerPathOf(it), uriOf(it)) }
  val result = QuickChatContextBuilder.build(
    question = question,
    fileName = quickChatFileName(file?.fullPath?.toString(), anchorFile, input?.name),
    text = document?.let(::DocumentTextWindow),
    selectionOffset = selection?.offset ?: 0,
    selectionLength = if (document == null) 0 else selection?.length ?: 0,
  )
  return CapturedContext(result, anchorFile)
}

/**
 * The popup's 1-based anchor line for [editor]'s current selection (I5, [anchorLine]); null without
 * a document or a text selection, or when the selection is not inside the document.
 */
fun anchorLineOf(editor: ITextEditor): Int? {
  val input = editor.editorInput ?: return null
  val document = editor.documentProvider?.getDocument(input) ?: return null
  val selection = editor.selectionProvider?.selection as? ITextSelection ?: return null
  return try {
    anchorLine(document::getLineOfOffset, document::getLineOffset, selection.offset, selection.length)
  } catch (ignored: BadLocationException) {
    null
  }
}

/**
 * I5: the 1-based line of the selection's end offset; a non-empty selection that ends exactly at a
 * line start (a whole-line selection) anchors on its last selected line instead. An empty selection
 * is the caret. [lineOfOffset] and [lineStartOffset] are 0-based, like `IDocument`'s.
 */
fun anchorLine(
  lineOfOffset: (Int) -> Int,
  lineStartOffset: (Int) -> Int,
  selectionOffset: Int,
  selectionLength: Int,
): Int {
  val end = selectionOffset + selectionLength
  val line = lineOfOffset(end)
  val stepBack = selectionLength > 0 && line > 0 && lineStartOffset(line) == end
  return (if (stepBack) line - 1 else line) + 1
}

/**
 * The file name sent with a selection (design §9.2.1): the workspace path without its leading `/`
 * when the input is a workspace file, otherwise the bare file name — never an absolute path, which
 * would carry the user's directory names.
 */
fun quickChatFileName(workspacePath: String?, anchorFile: File?, inputName: String?): String? = when {
  workspacePath != null -> workspacePath.removePrefix("/")
  anchorFile != null -> anchorFile.name
  inputName != null -> File(inputName).name
  else -> null
}

/**
 * The anchor file's location for the preflight (design §9.2.1, §9.2.2): the workspace file's
 * location, else the location provider's path, else a `file:` URI; null when none names a local file.
 */
fun anchorFileOf(workspaceLocation: File?, providerPath: File?, uri: URI?): File? =
  workspaceLocation ?: providerPath ?: uri?.let(::localFileOf)

private fun localFileOf(uri: URI): File? {
  if (!uri.scheme.equals("file", ignoreCase = true)) return null
  return try {
    File(uri)
  } catch (ignored: IllegalArgumentException) {
    null // a file: URI that names no plain path (authority, query, fragment)
  }
}

internal fun uriOf(input: IEditorInput): URI? = (input as? IURIEditorInput)?.uri

internal fun providerPathOf(input: IEditorInput): File? =
  input.getAdapter(ILocationProvider::class.java)?.getPath(input)?.toFile()

/** PR-1's [TextWindow] over an `IDocument`: only the requested window is copied out. */
class DocumentTextWindow(private val document: IDocument) : TextWindow {
  override val length: Int
    get() = document.length

  override fun get(offset: Int, length: Int): String = document.get(offset, length)
}
