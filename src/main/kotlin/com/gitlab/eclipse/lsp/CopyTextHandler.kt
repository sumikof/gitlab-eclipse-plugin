package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.lsp.messages.CopyTextParams
import com.gitlab.eclipse.lsp.messages.usablePayload
import com.gitlab.eclipse.navigation.ClipboardWriter
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger

/**
 * Copies the text the language server sends in `$/gitlab/copyText`, and says so — but only once
 * the text is really on the clipboard.
 *
 * A notification: there is no response, and no user-facing error either (a failed write is logged
 * by exception class name, never by content). What must be right is that "Copied to clipboard"
 * is never shown for a copy that did not happen, which [ClipboardWriter.writeAndNotify] enforces.
 *
 * Runs on the calling (lsp4j dispatch) thread; the write hops to the UI thread inside the writer
 * with `asyncExec`, so this returns at once and must never be wrapped in a `syncExec`.
 *
 * @property clipboard the writer; defaults to the real SWT clipboard
 * @property notify how the notice reaches the user; defaults to `NotificationUtils.show`, which
 *   marshals to the UI thread for itself
 */
class CopyTextHandler(
  private val clipboard: ClipboardWriter = ClipboardWriter(),
  private val notify: (String) -> Unit = { NotificationUtils.show(it) },
) {
  private val log by lazy { logger<CopyTextHandler>() }

  /** Copies the text [params] carries, or does nothing if it carries none. */
  fun handle(params: CopyTextParams) {
    val text = usablePayload(params.text)
    if (text == null) {
      log.info("copyText: the language server sent no usable text.")
      return
    }
    clipboard.writeAndNotify(text, notify)
  }
}
