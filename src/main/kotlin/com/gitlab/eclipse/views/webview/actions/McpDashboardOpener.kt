package com.gitlab.eclipse.views.webview.actions

import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.webview.WebviewEditorInput
import com.gitlab.eclipse.views.webview.WebviewEditorOpener
import org.eclipse.ui.PlatformUI

/**
 * Opens (or raises) the `root/mcp` webview in the active window's page. Design §7.5.
 *
 * Shared by the toolbar command ([ShowMcpDashboardHandler]) and the Agent Platform webview's
 * `openMcpDashboard` notification (AGENT-02 design §5.3), so both report a failure the same way.
 *
 * UI thread only — what design §15 requires of [WebviewEditorOpener], and what reading the active
 * page requires. The command reaches it on the workbench's dispatch thread; the notification hops
 * with `asyncExec` first.
 */
class McpDashboardOpener(private val wrapper: GitLabLanguageServerWrapper) {
  private val logger by lazy { logger<McpDashboardOpener>() }

  fun open() {
    // Design §12: neither failure below is left silent.
    val page = PlatformUI.getWorkbench().activeWorkbenchWindow?.activePage
    if (page == null) {
      logger.error("Cannot show webview '$WEBVIEW_ID': no active workbench page")
      NotificationUtils.show(FAILURE_MESSAGE)
      return
    }

    try {
      // `openOrReload` opens on the page it is handed (design §8.1) and reports nothing itself.
      WebviewEditorOpener(wrapper).openOrReload(page, WebviewEditorInput.mcp())
    } catch (e: Exception) {
      // Wider than the declared `PartInitException`, with two consequences. A failure inside
      // `openOrReload`'s activate-and-reload path is reported as "Could not open …" although the
      // tab is open and only its reload failed; and `OperationCanceledException` is a
      // `RuntimeException` in Eclipse, so a cancellation becomes an error notification. Both are
      // reported rather than swallowed, so neither breaches design §12.
      // Design §17: the type only.
      logger.error("Cannot show webview '$WEBVIEW_ID': cannot open the editor, type=${e.javaClass.name}")
      NotificationUtils.show(FAILURE_MESSAGE)
    }
  }

  companion object {
    const val FAILURE_MESSAGE = "Could not open the GitLab MCP Dashboard. See the Error Log."
    private const val WEBVIEW_ID = WebviewEditorInput.MCP_WEBVIEW_ID
  }
}
