package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.navigation.ClipboardWriter
import com.gitlab.eclipse.navigation.GitLabProjectUrlResolver
import com.gitlab.eclipse.navigation.WorkspaceProjectPicker
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent

@Suppress("unused")
class CopyLinkToClipboardHandler(
  private val picker: WorkspaceProjectPicker = WorkspaceProjectPicker(),
  private val clipboard: ClipboardWriter = ClipboardWriter(),
) : AbstractHandler() {
  private val logger = logger<CopyLinkToClipboardHandler>()

  override fun execute(event: ExecutionEvent): Any? {
    picker.pickWebUrl { r ->
      when (r) {
        is GitLabProjectUrlResolver.Resolution.Ok -> clipboard.write(r.url)
        is GitLabProjectUrlResolver.Resolution.Warn -> NotificationUtils.show(r.message)
      }
    }
    logger.info("copyLinkToClipboard requested.")
    return null
  }
}
