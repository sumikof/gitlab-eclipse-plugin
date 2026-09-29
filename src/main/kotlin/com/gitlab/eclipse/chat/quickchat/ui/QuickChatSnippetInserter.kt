package com.gitlab.eclipse.chat.quickchat.ui

import com.gitlab.eclipse.navigation.ClipboardWriter
import com.gitlab.eclipse.utils.CodeFormatter
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.inlinethread.CodeBlockAction
import org.eclipse.core.resources.IFile
import org.eclipse.core.runtime.CoreException
import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.ITextSelection
import org.eclipse.text.undo.DocumentUndoManagerRegistry
import org.eclipse.text.undo.IDocumentUndoManager
import org.eclipse.ui.texteditor.ITextEditor
import org.eclipse.ui.texteditor.ITextEditorExtension2
import java.io.File
import java.net.URI

/** JDT's content type for Java source files. */
const val JAVA_SOURCE_CONTENT_TYPE = "org.eclipse.jdt.core.javaSource"

/**
 * Design §9.6 step 4: a document is Java when its file's extension is `java`, or when its content
 * type (or one of its base types, [contentTypeIds]) is JDT's Java source.
 */
fun isJavaSource(fileExtension: String?, contentTypeIds: List<String>): Boolean =
  fileExtension.equals("java", ignoreCase = true) || JAVA_SOURCE_CONTENT_TYPE in contentTypeIds

/** The ids of [file]'s content type and its base types, nearest first; empty when it cannot be read. */
fun contentTypeIdsOf(file: IFile): List<String> {
  val type = try {
    file.contentDescription?.contentType
  } catch (ignored: CoreException) {
    null // a file that does not exist or is out of sync has no description: decided by extension alone
  }
  return generateSequence(type) { it.baseType }.map { it.id }.toList()
}

/**
 * Design §9.6 step 4: the formatter's result when there is a non-blank one, otherwise the code with
 * only its surrounding whitespace removed (not Java, the formatter threw, or it produced nothing).
 */
fun snippetToInsert(code: String, formatted: String?): String =
  formatted?.takeIf { it.isNotBlank() } ?: code.trim()

private fun documentUndoManagerOf(document: IDocument): IDocumentUndoManager? =
  DocumentUndoManagerRegistry.getDocumentUndoManager(document)

/**
 * The extension of a non-workspace input's file name, from the same sources as context capture
 * (`captureContext`): the location provider's path, else the URI's last path segment (an opaque URI
 * has none), else the input's name. Null when the chosen name has no extension. No file I/O.
 */
fun fileExtensionOf(providerPath: File?, uri: URI?, inputName: String?): String? {
  val name = providerPath?.name
    ?: uri?.path?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
    ?: inputName?.let { File(it).name }
    ?: return null
  return name.substringAfterLast('.', "").takeIf { it.isNotEmpty() }
}

/**
 * Whether [editor] holds Java: a workspace file by its extension and content type; any other input
 * (external file, URI-backed) by its file name's extension only ([fileExtensionOf]).
 */
fun isJavaEditor(editor: ITextEditor): Boolean {
  val input = editor.editorInput ?: return false
  val file = input.getAdapter(IFile::class.java)
    ?: return isJavaSource(fileExtensionOf(providerPathOf(input), uriOf(input), input.name), emptyList())
  return isJavaSource(file.fileExtension, contentTypeIdsOf(file))
}

/**
 * Inserts a Quick Chat code block into **the popup's editor**, never the active one (design §9.6,
 * FR-11). **UI thread only** — it runs from the block's Insert button.
 *
 * One `document.replace` inside one compound undo change, so a single Undo removes it; the caret
 * then goes to the end of the inserted text (I7). Every failure ends in a notification that names
 * neither the code nor the file, and the log gets exception class names only (NFR-4).
 *
 * @param notify shows a notification; production uses `NotificationUtils.show`.
 * @param undoManagerOf the document's undo manager; null when none is connected (the edit is still made).
 * @param isJava whether the editor holds a Java file ([isJavaEditor]).
 */
class QuickChatSnippetInserter(
  private val codeFormatter: CodeFormatter,
  private val notify: (String) -> Unit = { NotificationUtils.show(it) },
  private val undoManagerOf: (IDocument) -> IDocumentUndoManager? = ::documentUndoManagerOf,
  private val isJava: (ITextEditor) -> Boolean = ::isJavaEditor,
) {
  private val log by lazy { logger<QuickChatSnippetInserter>() }

  /** UI thread. Inserts [code] at [editor]'s current selection, replacing it. Never throws. */
  fun insert(editor: ITextEditor, code: String) {
    try {
      insertOrThrow(editor, code)
    } catch (e: Exception) {
      log.warn("Quick Chat insert failed: exceptionType=${e.javaClass.name}")
      notify(QuickChatUiTexts.INSERT_FAILED)
    }
  }

  private fun insertOrThrow(editor: ITextEditor, code: String) {
    // For a read-only file this is where Eclipse offers to check it out or make it writable.
    if ((editor as? ITextEditorExtension2)?.validateEditorInputState() == false) {
      notify(QuickChatUiTexts.EDITOR_NOT_EDITABLE)
      return
    }
    val document = editor.editorInput?.let { editor.documentProvider?.getDocument(it) }
    val selection = editor.selectionProvider?.selection as? ITextSelection
    if (document == null || selection == null) {
      log.warn("Quick Chat insert failed: document=${document != null} selection=${selection != null}")
      notify(QuickChatUiTexts.INSERT_FAILED)
      return
    }
    val formatted = if (isJava(editor)) formatOrNull(code, document, selection) else null
    val text = snippetToInsert(code, formatted)
    replaceAsOneUndo(document, selection.offset, selection.length, text)
    revealCaret(editor, selection.offset + text.length)
  }

  /** Design §27: JDT's formatter can fail on an answer that is not a whole compilation unit's worth of code. */
  private fun formatOrNull(code: String, document: IDocument, selection: ITextSelection): String? =
    try {
      codeFormatter.format(code, document, selection)
    } catch (e: Exception) {
      log.info("Quick Chat insert: formatting skipped: exceptionType=${e.javaClass.name}")
      null
    }

  /** Same shape as `WorkspaceEditApplier.applyEdits`: the compound change is ended whatever the replace did. */
  private fun replaceAsOneUndo(document: IDocument, offset: Int, length: Int, text: String) {
    val undoManager = undoManagerOf(document)
    undoManager?.beginCompoundChange()
    try {
      document.replace(offset, length, text)
    } finally {
      undoManager?.endCompoundChange()
    }
  }

  /** The text is already in; a caret that cannot be placed is not a failed insert, so it is only logged. */
  private fun revealCaret(editor: ITextEditor, offset: Int) {
    try {
      editor.selectAndReveal(offset, 0)
    } catch (e: Exception) {
      log.warn("Quick Chat insert: caret not placed: exceptionType=${e.javaClass.name}")
    }
  }
}

/**
 * A Quick Chat code block's Copy and Insert (design §9.6, §9.8), for one popup's [ITextEditor].
 * **UI thread only.** Copy's notice is shown only once the clipboard write has landed
 * ([ClipboardWriter.writeAndNotify]); a failed copy follows `ClipboardWriter`'s own handling.
 */
class QuickChatCodeActions(
  private val insert: (ITextEditor, String) -> Unit,
  private val clipboard: ClipboardWriter = ClipboardWriter(),
  private val notify: (String) -> Unit = { NotificationUtils.show(it) },
) {
  fun perform(editor: ITextEditor, action: CodeBlockAction, code: String) {
    when (action) {
      CodeBlockAction.COPY -> clipboard.writeAndNotify(code) { notify(QuickChatUiTexts.CODE_COPIED) }
      CodeBlockAction.INSERT -> insert(editor, code)
    }
  }
}
