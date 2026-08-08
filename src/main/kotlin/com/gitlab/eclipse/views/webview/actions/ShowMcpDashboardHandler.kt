package com.gitlab.eclipse.views.webview.actions

import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.webview.WebviewEditorInput
import com.gitlab.eclipse.views.webview.WebviewEditorOpener
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.PlatformUI

/** Opens the `root/mcp` webview in the editor area. Design §7.5. */
@Suppress("unused")
class ShowMcpDashboardHandler : AbstractHandler() {
  private val logger = logger<ShowMcpDashboardHandler>()
  private val languageServerWrapper by lazyService<GitLabLanguageServerWrapper>()

  /**
   * Everything here runs on the thread the workbench dispatches commands on, which is the UI
   * thread — what design §15 requires of [WebviewEditorOpener], and what reading the active page
   * requires. **Nothing in this class or its tests asserts it**; it rests on Eclipse's dispatch.
   */
  override fun execute(event: ExecutionEvent): Any? {
    // Design §12: neither failure below is left silent.
    val page = PlatformUI.getWorkbench().activeWorkbenchWindow?.activePage
    if (page == null) {
      logger.error("Cannot show webview '$WEBVIEW_ID': no active workbench page")
      NotificationUtils.show(FAILURE_MESSAGE)
      return null
    }

    try {
      // `openOrReload` opens on the page it is handed (design §8.1) and reports nothing itself.
      WebviewEditorOpener(languageServerWrapper).openOrReload(page, WebviewEditorInput.mcp())
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
    return null
  }

  private companion object {
    const val WEBVIEW_ID = WebviewEditorInput.MCP_WEBVIEW_ID
    const val FAILURE_MESSAGE = "Could not open the GitLab MCP Dashboard. See the Error Log."
  }
}
