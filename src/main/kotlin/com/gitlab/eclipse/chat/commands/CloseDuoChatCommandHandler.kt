package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.chat.utils.closeDuoChatWindow
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent

class CloseDuoChatCommandHandler : AbstractHandler() {
  override fun execute(event: ExecutionEvent) {
    closeDuoChatWindow()
  }
}
