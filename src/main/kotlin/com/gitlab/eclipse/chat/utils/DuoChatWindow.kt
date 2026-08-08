package com.gitlab.eclipse.chat.utils

import com.gitlab.eclipse.lsp.NewPromptRequest
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.LanguageServerBrowserView
import org.eclipse.ui.PlatformUI

private const val VIEW_ID = "com.gitlab.eclipse.views.LanguageServerBrowserView"

/** Worded like the three webview handlers' own failure messages, which point at the same log. */
private const val FAILURE_MESSAGE = "Could not open GitLab Duo Chat. See the Error Log."

// Lazy so that loading this file's class (e.g. mockkStatic in headless unit tests) does not
// touch the Eclipse Platform log. The type argument only selects the bundle whose log is used.
private val logger by lazy { logger<LanguageServerBrowserView>() }

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

/**
 * Reveals the Duo Chat view and hands [view] to the view's pending-intent API: the view
 * force-selects the agentic webview and asks the agentic client to switch to [view] only after
 * agentic is resolved and shown, so an agentic command issued while classic is selected is not
 * stranded.
 */
fun openDuoChatWindowWithAgenticView(view: String) {
  showDuoChatView()?.requestAgenticView(view)
}

/**
 * Switches the Duo Chat view to the webview [id] and re-resolves it: [LanguageServerBrowserView.selectWebview]
 * alone does not consult availability, so the follow-up [LanguageServerBrowserView.refresh] re-runs the
 * resolver, which falls back to another enabled webview when [id] is disabled and surfaces a
 * disabled reason only when no candidate is enabled.
 * A null [id] (the selector pulldown button itself) just reveals and re-resolves the view.
 */
fun selectDuoChatWebview(id: String?) {
  val view = showDuoChatView() ?: return
  if (id != null) {
    view.selectWebview(id)
  }
  view.refresh()
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

/**
 * Reveals the Duo Chat view, or reports why it could not be.
 *
 * All three ways this can fail are design §12's `showView` row and all three report through both
 * channels: no active page, `showView` throwing, and a part that is not the expected type. They are
 * reported here rather than in each caller so the callers cannot drift apart, which is what
 * `ShowAgenticTabsHandler` / `ShowMcpDashboardHandler` / `OpenFlowBuilderHandler` each do once for
 * their own surface.
 *
 * **The first and third were raised from `warn` to `error` and gained the notification**, so the
 * classic Duo Chat commands that predate this file's agentic callers now report a failure they used
 * to record quietly.
 *
 * The second — `showView`'s declared `PartInitException` — was previously left to propagate.
 * Catching it adds four instructions to the success path (a `nop`, a `goto` and a slot copy, none
 * with an observable effect) and closes the row rather than two of its three causes.
 */
private fun showDuoChatView(): LanguageServerBrowserView? {
  val page = PlatformUI.getWorkbench().activeWorkbenchWindow?.activePage
  if (page == null) {
    reportCannotShow("no active workbench page")
    return null
  }

  val part = try {
    page.showView(VIEW_ID)
  } catch (e: Exception) {
    // Wider than the declared `PartInitException`, for the reason the three webview handlers give:
    // part creation can fail with a `RuntimeException`, and to the user that is the same failure.
    // Design §17: the type only — never the exception, whose message is outside our control.
    reportCannotShow("type=${e.javaClass.name}")
    return null
  }

  val view = part as? LanguageServerBrowserView
  if (view == null) {
    reportCannotShow("'$VIEW_ID' did not resolve to LanguageServerBrowserView")
  }
  return view
}

/** Design §12: the Error Log entry and the notification, never one without the other. */
private fun reportCannotShow(reason: String) {
  logger.error("Cannot show the Duo Chat view: $reason")
  NotificationUtils.show(FAILURE_MESSAGE)
}
