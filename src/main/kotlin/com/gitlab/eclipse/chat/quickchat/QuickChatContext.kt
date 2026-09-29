package com.gitlab.eclipse.chat.quickchat

/**
 * A non-SWT snapshot of the anchor editor's file and the text immediately around the selection,
 * fixed on the UI thread before the request crosses to the background (design §9.2.1, §9.2.5).
 *
 * [fileName] is the workspace-relative path when known, else the bare file name — never an
 * absolute path (design §9.2.1, NFR-4: paths are not logged, but they are still sent to the
 * server as part of the request body by design, same as the reference implementation).
 */
data class CurrentFile(
  val fileName: String,
  val selectedText: String,
  val contentAboveCursor: String,
  val contentBelowCursor: String,
)

/** The immutable payload [QuickChatContextBuilder] hands to the background sender. */
data class QuickChatContext(val question: String, val currentFile: CurrentFile?)

/**
 * The only way [QuickChatContextBuilder] reads the anchor document — PR-2 adapts `IDocument` to
 * this, keeping this module SWT/JFace-free (design §8.1). The selection is given to the builder
 * separately as `(offset, length)`; [get] is used only to read the fixed windows this class needs.
 */
interface TextWindow {
  /** The document's total length in characters. */
  val length: Int

  /** Returns the [length] characters starting at [offset]. */
  fun get(offset: Int, length: Int): String
}
