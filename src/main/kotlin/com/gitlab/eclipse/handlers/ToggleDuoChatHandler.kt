package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.commands.ExecutionException
import org.eclipse.jface.dialogs.MessageDialog
import org.eclipse.ui.handlers.HandlerUtil

@Suppress("unused")
class ToggleDuoChatHandler : AbstractHandler() {
  val logger = logger<ToggleDuoChatHandler>()

  @Throws(ExecutionException::class)
  override fun execute(event: ExecutionEvent): Any? {
    val shell = HandlerUtil.getActiveWorkbenchWindow(event)?.shell ?: return null

    // TODO: Toggle the workspace's chat enabled/disabled status?
    MessageDialog.openInformation(
      shell,
      "Gitlab Eclipse Plugin",
      "This is an example message dialog. Would you like to enable Duo?"
    )
    return null
  }
}
