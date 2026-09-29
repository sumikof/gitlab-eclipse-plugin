package com.gitlab.eclipse.chat.quickchat.ui

import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.commands.HandlerEvent
import org.eclipse.swt.widgets.Display
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.handlers.HandlerUtil

/**
 * Design §9.5: "Close Quick Chat" is enabled only while the active window shows a Quick Chat. The
 * handler service may ask off the UI thread, where the workbench must not be touched: there it
 * answers from [openWindows] alone (any window shows one) and never calls [activeWindow].
 */
internal fun <W : Any> closeQuickChatEnabled(
  onUiThread: Boolean,
  activeWindow: () -> W?,
  openWindows: Set<W>,
): Boolean =
  if (onUiThread) activeWindow()?.let { it in openWindows } == true else openWindows.isNotEmpty()

/**
 * "Close Quick Chat" (design §9.5, §11.1): closes the active window's Quick Chat the same way its
 * Esc / close button do (unsent drafts offered). No key binding; run from Quick Access.
 *
 * Enablement is computed on every query from [QuickChatPopups.openWindows] (a thread-safe
 * snapshot), and the handler announces a change of it itself: popups open and close without any
 * event the handler service would re-evaluate on.
 */
@Suppress("unused")
class CloseQuickChatHandler : AbstractHandler() {
  /** Runs on the UI thread, where [OpenWindows.publish] runs. */
  private val enablementChanged: () -> Unit = { fireHandlerChanged(HandlerEvent(this, true, false)) }

  init {
    QuickChatPopups.openWindows.addListener(enablementChanged)
  }

  override fun isEnabled(): Boolean =
    closeQuickChatEnabled(Display.getCurrent() != null, ::activeWindow, QuickChatPopups.openWindows.current)

  override fun execute(event: ExecutionEvent): Any? {
    val window = HandlerUtil.getActiveWorkbenchWindow(event) ?: return null
    QuickChatPopups.closeIn(window)
    return null
  }

  override fun dispose() {
    QuickChatPopups.openWindows.removeListener(enablementChanged)
    super.dispose()
  }

  private fun activeWindow(): IWorkbenchWindow? =
    if (PlatformUI.isWorkbenchRunning()) PlatformUI.getWorkbench().activeWorkbenchWindow else null
}
