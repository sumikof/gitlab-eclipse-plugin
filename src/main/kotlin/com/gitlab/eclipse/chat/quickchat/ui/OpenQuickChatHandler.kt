package com.gitlab.eclipse.chat.quickchat.ui

import com.gitlab.eclipse.chat.DuoChatStateService
import com.gitlab.eclipse.chat.quickchat.duoChatAvailability
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.PlatformUtils
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.texteditor.ITextEditor

/**
 * Design §9.1 steps 1–2, SWT-free. [unavailableReason] is re-checked first because the handler's
 * `duo_chat_enabled` enablement can lag behind the language server: a reason means one notification
 * and nothing else. Without an active text editor, or a line to anchor on, nothing happens.
 */
internal fun openQuickChat(
  unavailableReason: () -> String?,
  notify: (String) -> Unit,
  activeEditor: () -> ITextEditor?,
  anchorLine: (ITextEditor) -> Int?,
  open: (ITextEditor, Int) -> Unit,
) {
  unavailableReason()?.let { reason ->
    notify(reason)
    return
  }
  val editor = activeEditor() ?: return
  val line = anchorLine(editor) ?: return
  open(editor, line)
}

/**
 * "Open Quick Chat" (design §9.1, §11.1): `M1+M3+C` in text editors and the editor context menu's
 * "GitLab Duo Chat" submenu. Enabled while `duo_chat_enabled` (plugin.xml). UI thread.
 */
@Suppress("unused")
class OpenQuickChatHandler : AbstractHandler() {
  override fun execute(event: ExecutionEvent): Any? {
    openQuickChat(
      unavailableReason = duoChatAvailability(service<DuoChatStateService>()),
      notify = NotificationUtils::showOnUiThread,
      // UI thread, so a multi-page editor's ITextEditor adapter is found too (design §9.1 step 2).
      activeEditor = { PlatformUtils().getActiveTextEditor() },
      anchorLine = ::anchorLineOf,
      open = QuickChatPopups::open,
    )
    return null
  }
}
