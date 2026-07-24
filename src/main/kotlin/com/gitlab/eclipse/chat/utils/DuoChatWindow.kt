package com.gitlab.eclipse.chat.utils

import com.gitlab.eclipse.lsp.NewPromptRequest
import com.gitlab.eclipse.views.LanguageServerBrowserView
import org.eclipse.ui.PlatformUI

private const val VIEW_ID = "com.gitlab.eclipse.views.LanguageServerBrowserView"

fun openDuoChatWindow() {
  // Deliberately no direct classic-client notification: the view flushes a `focusChat` prompt
  // only when the classic webview is actually shown, so an agentic-only selection does not
  // strand the message in the classic client's push queue.
  showDuoChatView()?.requestFocus()
}

/**
 * Reveals the Duo Chat view and hands [payload] to the view's pending-intent API: the view
 * force-selects the classic webview and flushes the prompt only after classic is resolved and
 * shown, so a classic-only command issued while agentic is selected is not stranded.
 */
fun openDuoChatWindowWithClassicPrompt(payload: NewPromptRequest) {
  showDuoChatView()?.requestClassicPrompt(payload)
}

fun closeDuoChatWindow() {
  val page = PlatformUI.getWorkbench().activeWorkbenchWindow?.activePage ?: return
  val view = page.findView(VIEW_ID) ?: return

  // Deliberately no webview notification: GitLabDuoChatWebViewClient queues messages while
  // the webview is unfocused, so anything sent here would be stranded.
  page.hideView(view)
}

fun refreshDuoChatWindow() {
  val workbench = PlatformUI.getWorkbench()

  val page = workbench.activeWorkbenchWindow?.activePage
    ?: workbench.workbenchWindows.getOrNull(0)?.activePage
    ?: return

  val view = page.findView(VIEW_ID) as? LanguageServerBrowserView ?: return
  view.refresh()
}

private fun showDuoChatView(): LanguageServerBrowserView? {
  val page = PlatformUI.getWorkbench().activeWorkbenchWindow?.activePage ?: return null
  return page.showView(VIEW_ID) as? LanguageServerBrowserView
}
