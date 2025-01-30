package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.chat.utils.openDuoChatWindow
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent

class OpenDuoChatCommandHandler : AbstractHandler() {
  override fun execute(event: ExecutionEvent) {
    openDuoChatWindow()
  }
}
