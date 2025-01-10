package com.gitlab.eclipse.chat.utils

import org.eclipse.ui.PlatformUI

fun openDuoChatWindow() {
  val page = PlatformUI.getWorkbench().activeWorkbenchWindow?.activePage ?: return
  page.showView("com.gitlab.eclipse.views.LanguageServerBrowserView")
}
