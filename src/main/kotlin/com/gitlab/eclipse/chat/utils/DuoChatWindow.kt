package com.gitlab.eclipse.chat.utils

import com.gitlab.eclipse.views.LanguageServerBrowserView
import org.eclipse.ui.PlatformUI

private const val VIEW_ID = "com.gitlab.eclipse.views.LanguageServerBrowserView"

fun openDuoChatWindow() {
  val page = PlatformUI.getWorkbench().activeWorkbenchWindow?.activePage ?: return
  page.showView(VIEW_ID)
}

fun refreshDuoChatWindow() {
  val workbench = PlatformUI.getWorkbench()

  val page = workbench.activeWorkbenchWindow?.activePage
    ?: workbench.workbenchWindows.getOrNull(0)?.activePage
    ?: return

  val view = page.findView(VIEW_ID) as? LanguageServerBrowserView ?: return
  view.refresh()
}
