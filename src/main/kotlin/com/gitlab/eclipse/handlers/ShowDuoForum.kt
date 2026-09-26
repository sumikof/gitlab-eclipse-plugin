package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.navigation.BrowserLauncher
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent

/** Status menu "GitLab Forum (Help and feedback)": opens the GitLab Duo forum category in the external browser. */
@Suppress("unused")
class ShowDuoForum(
  private val browser: BrowserLauncher = BrowserLauncher(),
) : AbstractHandler() {
  private val logger = logger<ShowDuoForum>()

  override fun execute(event: ExecutionEvent): Any? {
    logger.info("Opening the GitLab Duo forum: $URL")
    browser.open(URL)
    return null
  }

  companion object {
    /** The reference extension's `GITLAB_FORUM_URL` (`src/common/duo_quick_pick/constants.ts:18`). */
    const val URL = "https://forum.gitlab.com/c/gitlab-duo/52"
  }
}
