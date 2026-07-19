package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.issues.IssuesView
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.PlatformUI

private const val ISSUES_VIEW_ID = "com.gitlab.eclipse.views.IssuesView"

@Suppress("unused")
class ShowIssuesHandler : AbstractHandler() {
  private val logger = logger<ShowIssuesHandler>()

  override fun execute(event: ExecutionEvent): Any? {
    val page = PlatformUI.getWorkbench().activeWorkbenchWindow?.activePage ?: return null
    val view = page.showView(ISSUES_VIEW_ID) as? IssuesView
    view?.refresh()
    logger.info("Opened GitLab Issues view.")
    return null
  }
}
