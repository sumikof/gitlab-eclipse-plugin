package com.gitlab.eclipse.views.webview.agentplatform

import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.webview.AgenticTabsView
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.ui.handlers.HandlerUtil

/**
 * "Reload" in the Agent Platform view's pulldown (`com.gitlab.eclipse.commands.ReloadAgentPlatformView`,
 * design AGENT-02 §5.1 / §6): reloads the view of the window the command came from, which re-resolves
 * the webview metadata for the current layout.
 *
 * Runs on the thread the workbench dispatches commands on, which is the UI thread
 * [AgenticTabsView.reloadFromMenu] requires; nothing here asserts it. The view not being open on the
 * page is not a failure (there is nothing to reload). No page, or a reload that throws, is logged
 * (exception class only) and reported.
 *
 * Instantiated by the workbench from `plugin.xml`; the parameters are seams for headless tests.
 */
class ReloadAgentPlatformViewHandler(
  private val activePage: (ExecutionEvent) -> IWorkbenchPage? = {
    HandlerUtil.getActiveWorkbenchWindow(it)?.activePage
  },
  private val notify: (String) -> Unit = { NotificationUtils.show(it) },
) : AbstractHandler() {
  override fun execute(event: ExecutionEvent): Any? {
    try {
      val page = activePage(event)
      if (page == null) {
        logger<ReloadAgentPlatformViewHandler>().error("Agent Platform reload: no active workbench page")
        notify(AgentPlatformMessages.RELOAD_FAILURE)
        return null
      }
      (page.findView(AgenticTabsView.VIEW_ID) as? AgenticTabsView)?.reloadFromMenu()
    } catch (e: Exception) {
      logger<ReloadAgentPlatformViewHandler>().error("Agent Platform reload failed: ${e::class.java.name}")
      notify(AgentPlatformMessages.RELOAD_FAILURE)
    }
    return null
  }
}
