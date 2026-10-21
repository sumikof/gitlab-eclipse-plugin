package com.gitlab.eclipse.lsp.messages

import com.gitlab.eclipse.utils.ActionNotifications
import org.eclipse.jface.dialogs.MessageDialog
import org.eclipse.lsp4j.MessageType
import org.eclipse.swt.SWT
import org.eclipse.swt.widgets.Shell
import org.eclipse.ui.PlatformUI

private const val TITLE = "GitLab Duo"
private const val AUTO_CLOSE_MILLIS = 10_000L

/*
 * The SWT side of LanguageServerMessagePresenter: thin on purpose, it needs a Display and cannot
 * be unit-tested headless. Every decision (gates, dedupe, result mapping, completion thread) lives
 * in the presenter. Both functions run on the UI thread.
 */

/** Default `notify`: an [ActionNotifications] popup; Error stays until closed, the others close after 10 s. */
internal fun presentMessage(type: MessageType, text: String) {
  ActionNotifications.show(
    title = TITLE,
    message = text,
    severity = when (type) {
      MessageType.Error -> SWT.ICON_ERROR
      MessageType.Warning -> SWT.ICON_WARNING
      else -> SWT.ICON_INFORMATION
    },
    links = emptyList(),
    autoCloseMillis = if (type == MessageType.Error) 0 else AUTO_CLOSE_MILLIS,
    // Already on the UI thread, inside the presenter's gated turn.
    onUiThread = { it.run() },
  )
}

/** Default `ask`: a modal [MessageDialog] over the active workbench shell; returns the pressed button index. */
internal fun askOnUiThread(
  type: MessageType,
  text: String,
  buttons: List<String>,
  onDialog: (DialogHandle) -> Unit,
): Int {
  val kind = when (type) {
    MessageType.Error -> MessageDialog.ERROR
    MessageType.Warning -> MessageDialog.WARNING
    else -> MessageDialog.INFORMATION
  }
  // '&' in a button label would otherwise be read as a mnemonic marker.
  val labels = buttons.map { it.replace("&", "&&") }.toTypedArray()
  val dialog = LanguageServerMessageDialog(activeShell(), kind, text, labels)
  onDialog(dialog)
  return dialog.open()
}

private fun activeShell(): Shell? = PlatformUI.getWorkbench().activeWorkbenchWindow?.shell

/**
 * A [MessageDialog] that can be closed without a choice. JFace `Window.close()` leaves the return
 * code untouched and `open()` would then report `OK` (0), which maps to the first action (design
 * §6 F4); `setReturnCode` is protected, hence this subclass (§15 R2-I3).
 */
@Suppress("SpreadOperator") // JFace takes the button labels as a vararg; a handful, once per dialog.
private class LanguageServerMessageDialog(
  parent: Shell?,
  kind: Int,
  message: String,
  buttons: Array<String>,
) : MessageDialog(parent, TITLE, null, message, kind, 0, *buttons), DialogHandle {
  override fun dismiss() {
    returnCode = SWT.DEFAULT
    close()
  }
}
