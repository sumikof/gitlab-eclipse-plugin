package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.lsp.ShowDocumentLauncher
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent

/**
 * Status menu "GitLab Forum (Help and feedback)": opens the GitLab Duo forum category in the
 * external browser.
 *
 * Opens it through [ShowDocumentLauncher], which goes through the plugin's one browser gate,
 * [com.gitlab.eclipse.navigation.BrowserLauncher.openAsync]: the gate never logs the URL or an
 * exception message, and does not notify. The returned future is ignored: it never completes
 * exceptionally, and there is nothing to react to.
 */
@Suppress("unused")
class ShowDuoForum(
  private val launcher: ShowDocumentLauncher = ShowDocumentLauncher(),
) : AbstractHandler() {
  private val logger = logger<ShowDuoForum>()

  override fun execute(event: ExecutionEvent): Any? {
    logger.info("Opening GitLab Forum")
    launcher.show(URL)
    return null
  }

  companion object {
    /** The reference extension's `GITLAB_FORUM_URL` (`src/common/duo_quick_pick/constants.ts:18`). */
    const val URL = "https://forum.gitlab.com/c/gitlab-duo/52"
  }
}
