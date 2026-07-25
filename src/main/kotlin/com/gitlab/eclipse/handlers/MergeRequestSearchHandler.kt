package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.navigation.BrowserLauncher
import com.gitlab.eclipse.navigation.GitLabProjectUrlResolver
import com.gitlab.eclipse.navigation.SearchQueryBuilder
import com.gitlab.eclipse.navigation.WorkspaceProjectPicker
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.jface.dialogs.InputDialog
import org.eclipse.jface.window.Window
import org.eclipse.ui.PlatformUI

@Suppress("unused")
class MergeRequestSearchHandler(
  private val picker: WorkspaceProjectPicker = WorkspaceProjectPicker(),
  private val browser: BrowserLauncher = BrowserLauncher(),
) : AbstractHandler() {
  private val logger = logger<MergeRequestSearchHandler>()

  private val noteableType = "merge_requests"
  private val pathSegment = "merge_requests"
  private val prompt = "Search in title or description."

  override fun execute(event: ExecutionEvent): Any? {
    val text = promptForText(prompt) ?: return null
    val query = SearchQueryBuilder.parseQuery(text, noteableType)
    picker.pickWebUrl { r ->
      when (r) {
        is GitLabProjectUrlResolver.Resolution.Ok -> browser.open("${r.url}/-/$pathSegment$query")
        is GitLabProjectUrlResolver.Resolution.Warn -> NotificationUtils.show(r.message)
      }
    }
    logger.info("mergeRequestSearch requested.")
    return null
  }

  private fun promptForText(message: String): String? {
    val shell = PlatformUI.getWorkbench().activeWorkbenchWindow?.shell
    val dialog = InputDialog(shell, "GitLab Search", message, "", null)
    if (dialog.open() != Window.OK) return null
    return dialog.value?.trim()?.takeIf { it.isNotEmpty() }
  }
}
