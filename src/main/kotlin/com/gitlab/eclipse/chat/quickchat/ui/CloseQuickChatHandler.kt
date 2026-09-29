package com.gitlab.eclipse.chat.quickchat.ui

import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.handlers.HandlerUtil

/** Design §9.5: "Close Quick Chat" is enabled only while [window] (the active one) shows a Quick Chat. */
internal fun closeQuickChatEnabled(window: IWorkbenchWindow?, isOpenIn: (IWorkbenchWindow) -> Boolean): Boolean =
  window != null && isOpenIn(window)

/**
 * "Close Quick Chat" (design §9.5, §11.1): closes the active window's Quick Chat the same way its
 * Esc / close button do (unsent drafts offered). No key binding; run from Quick Access. UI thread.
 */
@Suppress("unused")
class CloseQuickChatHandler : AbstractHandler() {
  // Computed on every query rather than cached: popups open and close without any event the
  // handler service would re-evaluate on.
  override fun isEnabled(): Boolean =
    closeQuickChatEnabled(activeWindow(), QuickChatPopups::isOpenIn)

  override fun execute(event: ExecutionEvent): Any? {
    val window = HandlerUtil.getActiveWorkbenchWindow(event) ?: return null
    QuickChatPopups.closeIn(window)
    return null
  }

  private fun activeWindow(): IWorkbenchWindow? =
    if (PlatformUI.isWorkbenchRunning()) PlatformUI.getWorkbench().activeWorkbenchWindow else null
}
