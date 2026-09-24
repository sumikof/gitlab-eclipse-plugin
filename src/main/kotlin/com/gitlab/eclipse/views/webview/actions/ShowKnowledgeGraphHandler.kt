package com.gitlab.eclipse.views.webview.actions

import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.knowledgegraph.KnowledgeGraphCommand
import com.gitlab.eclipse.knowledgegraph.KnowledgeGraphState
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.webview.WebviewEditorInput
import com.gitlab.eclipse.views.webview.WebviewEditorOpener
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.ui.PlatformUI

/**
 * `gitlab-eclipse-plugin.commands.ShowKnowledgeGraph` — opens the Knowledge Graph in the editor area
 * (plan §9.3). A thin shell over [KnowledgeGraphCommand], which decides whether to ask the server for
 * the address first. Always enabled (design U5): without `gkg` the tab explains what is missing.
 */
@Suppress("unused")
class ShowKnowledgeGraphHandler : AbstractHandler() {
  private val logger = logger<ShowKnowledgeGraphHandler>()
  private val languageServerWrapper by lazyService<GitLabLanguageServerWrapper>()

  /**
   * Runs on the thread the workbench dispatches commands on, the UI thread, which reading the active
   * page requires. The page is captured now: the tab opens on the page the user ran the command in,
   * even when it opens only after the server has answered.
   */
  override fun execute(event: ExecutionEvent): Any? {
    val page = PlatformUI.getWorkbench().activeWorkbenchWindow?.activePage
    if (page == null) {
      logger.error("Cannot show webview '$WEBVIEW_ID': no active workbench page")
      NotificationUtils.show(FAILURE_MESSAGE)
      return null
    }

    KnowledgeGraphCommand({ languageServerWrapper.currentSnapshot }).run { open(page) }
    return null
  }

  /** On the UI thread. Failures handled like `ShowMcpDashboardHandler`'s: the type only, and a notification. */
  @Suppress("TooGenericExceptionCaught")
  private fun open(page: IWorkbenchPage) {
    try {
      WebviewEditorOpener(languageServerWrapper).openOrReload(page, WebviewEditorInput.knowledgeGraph())
    } catch (e: Exception) {
      logger.error("Cannot show webview '$WEBVIEW_ID': cannot open the editor, type=${e.javaClass.name}")
      NotificationUtils.show(FAILURE_MESSAGE)
    }
  }

  private companion object {
    const val WEBVIEW_ID = KnowledgeGraphState.WEBVIEW_ID
    const val FAILURE_MESSAGE = "Could not open the GitLab Knowledge Graph. See the Error Log."
  }
}
