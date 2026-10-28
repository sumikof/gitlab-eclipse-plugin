package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.lsp.ShowDocumentLauncher
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent

/**
 * Status menu "GitLab Duo Documentation": opens the GitLab Duo product documentation in the external
 * browser. Distinct from [ShowDocumentation], which opens this plugin's own documentation.
 *
 * Opens it through [ShowDocumentLauncher], which goes through the plugin's one browser gate,
 * [com.gitlab.eclipse.navigation.BrowserLauncher.openAsync]: the gate never logs the URL or an
 * exception message, and does not notify. The returned future is ignored: it never completes
 * exceptionally, and there is nothing to react to.
 */
@Suppress("unused")
class ShowDuoDocumentation(
  private val launcher: ShowDocumentLauncher = ShowDocumentLauncher(),
) : AbstractHandler() {
  private val logger = logger<ShowDuoDocumentation>()

  override fun execute(event: ExecutionEvent): Any? {
    logger.info("Opening GitLab Duo documentation")
    launcher.show(URL)
    return null
  }

  companion object {
    /** The reference extension's `DOCUMENTATION_URL` (`src/common/duo_quick_pick/constants.ts:17`). */
    const val URL = "https://docs.gitlab.com/user/gitlab_duo/"
  }
}
