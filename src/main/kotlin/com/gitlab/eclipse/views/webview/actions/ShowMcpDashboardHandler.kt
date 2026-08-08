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
