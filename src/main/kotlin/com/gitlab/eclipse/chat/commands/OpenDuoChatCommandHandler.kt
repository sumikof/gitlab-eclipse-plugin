package com.gitlab.eclipse.chat.commands

import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.PlatformUI

class OpenDuoChatCommandHandler : AbstractHandler() {
  override fun execute(event: ExecutionEvent) {
    val page = PlatformUI.getWorkbench().activeWorkbenchWindow?.activePage ?: return
    page.showView("TODO_ADD_VIEW_ID_HERE")
  }
}
