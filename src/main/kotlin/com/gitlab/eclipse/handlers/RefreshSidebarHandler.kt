package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.views.sidebar.GitLabSidebarView
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.PlatformUI

/**
 * Reloads the GitLab sidebar ([GitLabSidebarView.refresh] re-fetches issues and MRs).
 *
 * If the view is not open there is nothing to refresh, so this is a silent no-op
 * (the view refreshes itself on open anyway).
 */
@Suppress("unused")
class RefreshSidebarHandler : AbstractHandler() {
  override fun execute(event: ExecutionEvent): Any? {
    val page = PlatformUI.getWorkbench().activeWorkbenchWindow?.activePage ?: return null
    (page.findView(GitLabSidebarView.VIEW_ID) as? GitLabSidebarView)?.refresh()
    return null
  }
}
