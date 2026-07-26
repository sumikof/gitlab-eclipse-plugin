package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.sidebar.GitLabSidebarView
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.PlatformUI

/**
 * Opens the GitLab sidebar (which contains the "Issues assigned to me" root) and refreshes it.
 */
@Suppress("unused")
class ShowIssuesHandler : AbstractHandler() {
  private val logger = logger<ShowIssuesHandler>()

  override fun execute(event: ExecutionEvent): Any? {
    val page = PlatformUI.getWorkbench().activeWorkbenchWindow?.activePage ?: return null
    val view = page.showView(GitLabSidebarView.VIEW_ID) as? GitLabSidebarView
    view?.refresh()
    logger.info("Opened GitLab sidebar view.")
    return null
  }
}
