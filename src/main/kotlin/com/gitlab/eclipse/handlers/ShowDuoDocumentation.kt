package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.navigation.BrowserLauncher
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent

/**
 * Status menu "GitLab Duo Documentation": opens the GitLab Duo product documentation in the external
 * browser. Distinct from [ShowDocumentation], which opens this plugin's own documentation.
 */
@Suppress("unused")
class ShowDuoDocumentation(
  private val browser: BrowserLauncher = BrowserLauncher(),
) : AbstractHandler() {
  private val logger = logger<ShowDuoDocumentation>()

  override fun execute(event: ExecutionEvent): Any? {
    logger.info("Opening the GitLab Duo documentation: $URL")
    browser.open(URL)
    return null
  }

  companion object {
    /** The reference extension's `DOCUMENTATION_URL` (`src/common/duo_quick_pick/constants.ts:17`). */
    const val URL = "https://docs.gitlab.com/user/gitlab_duo/"
  }
}
