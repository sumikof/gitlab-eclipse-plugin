package com.gitlab.eclipse.views.webview.actions

import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.webview.AgenticTabsView
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.PlatformUI

/** Opens the `agentic-tabs` view. Design §7.5. */
@Suppress("unused")
class ShowAgenticTabsHandler : AbstractHandler() {
  private val logger = logger<ShowAgenticTabsHandler>()

  override fun execute(event: ExecutionEvent): Any? {
    // Design §12: neither failure below is left silent.
    val page = PlatformUI.getWorkbench().activeWorkbenchWindow?.activePage
    if (page == null) {
      logger.error("Cannot open the GitLab Duo Agent Platform view: no active workbench page")
      NotificationUtils.show(FAILURE_MESSAGE)
      return null
    }

    try {
      page.showView(AgenticTabsView.VIEW_ID)
    } catch (e: Exception) {
      // Design §17: the type only.
      logger.error("Cannot open the GitLab Duo Agent Platform view: type=${e.javaClass.name}")
      NotificationUtils.show(FAILURE_MESSAGE)
    }
    return null
  }

  private companion object {
    const val FAILURE_MESSAGE = "Could not open GitLab Duo Agent Platform. See the Error Log."
  }
}
