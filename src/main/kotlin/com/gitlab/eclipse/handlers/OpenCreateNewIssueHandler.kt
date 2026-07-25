package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.navigation.BrowserLauncher
import com.gitlab.eclipse.navigation.GitLabProjectUrlResolver
import com.gitlab.eclipse.navigation.WorkspaceProjectPicker
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent

@Suppress("unused")
class OpenCreateNewIssueHandler(
  private val picker: WorkspaceProjectPicker = WorkspaceProjectPicker(),
  private val browser: BrowserLauncher = BrowserLauncher(),
) : AbstractHandler() {
  private val logger = logger<OpenCreateNewIssueHandler>()

  override fun execute(event: ExecutionEvent): Any? {
    picker.pickWebUrl { r ->
      when (r) {
        is GitLabProjectUrlResolver.Resolution.Ok -> browser.open("${r.url}/-/issues/new")
        is GitLabProjectUrlResolver.Resolution.Warn -> NotificationUtils.show(r.message)
      }
    }
    logger.info("openCreateNewIssue requested.")
    return null
  }
}
