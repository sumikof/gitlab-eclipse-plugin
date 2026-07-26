package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.views.sidebar.SidebarViewMode
import com.gitlab.eclipse.views.sidebar.SidebarViewState
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent

/**
 * Switches the GitLab sidebar to grouped tree mode. The shared [SidebarViewState] notifies
 * the sidebar view, which re-composes from its cached results without re-fetching.
 */
@Suppress("unused")
class SidebarViewAsTreeHandler : AbstractHandler() {
  private val viewState by lazyService<SidebarViewState>()

  override fun execute(event: ExecutionEvent): Any? {
    viewState.mode = SidebarViewMode.TREE
    return null
  }
}
