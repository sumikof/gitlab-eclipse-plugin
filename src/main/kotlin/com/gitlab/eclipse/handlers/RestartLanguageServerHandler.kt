package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.GitLabLanguageServerProcessProvider
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
    // restart() blocks while the server stops and starts, so run it off the UI thread.
    service<CoroutineScope>().launch {
      val restarted = service<GitLabLanguageServerProcessProvider>().restart(bundle)
      if (restarted) {
        NotificationUtils.show("GitLab Language Server restarted.")
      } else {
        NotificationUtils.show(
          "GitLab Language Server restart failed. Check the error log and run the command again to retry."
        )
      }
    }
    return null
  }
}
