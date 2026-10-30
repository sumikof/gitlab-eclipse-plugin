package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.GitLabLanguageServerProcessProvider
import com.gitlab.eclipse.lsp.RestartOutcome
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.osgi.framework.FrameworkUtil

@Suppress("unused")
class RestartLanguageServerHandler : AbstractHandler() {
  private val logger by lazy { logger<RestartLanguageServerHandler>() }

  override fun execute(event: ExecutionEvent): Any? {
    val bundle = FrameworkUtil.getBundle(GitLabLanguageServerProcessProvider::class.java)
    if (bundle == null) {
      logger.error("Could not resolve the plugin bundle; cannot restart the Language Server.")
      return null
    }

    NotificationUtils.show("Restarting the GitLab Language Server...")
    // restartForOutcome() blocks while the server stops and starts, so run it off the UI thread.
    service<CoroutineScope>().launch {
      // The shared CoroutineScope has a plain Job: an escaped exception would cancel it
      // for every other user, so nothing may propagate out of this launch.
      val outcome = try {
        service<GitLabLanguageServerProcessProvider>().restartForOutcome(bundle)
      } catch (e: Exception) {
        logger.error("Unexpected failure while restarting the Language Server.", e)
        RestartOutcome.FAILED
      }
      when (outcome) {
        RestartOutcome.RESTARTED -> NotificationUtils.show("GitLab Language Server restarted.")
        RestartOutcome.FAILED -> NotificationUtils.show(
          "GitLab Language Server restart failed. Check the error log and run the command again to retry."
        )
        RestartOutcome.ABANDONED -> logger.info("The Language Server restart was abandoned: the plugin is stopping.")
      }
    }
    return null
  }
}
