package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.preferences.openGitLabPreferences
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.commands.ExecutionException

@Suppress("unused")
class ShowSettings : AbstractHandler() {
  val logger = logger<ShowSettings>()

  @Throws(ExecutionException::class)
  override fun execute(event: ExecutionEvent): Any? {
    logger.info("Showing GitLab for Eclipse settings.")
    openGitLabPreferences()
    return null
  }
}
