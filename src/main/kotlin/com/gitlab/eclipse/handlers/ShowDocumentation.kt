package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.navigation.BrowserLauncher
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.commands.ExecutionException

/**
 * Opens the GitLab for Eclipse documentation through the one external-browser gate ([BrowserLauncher], D8a): a
 * failure is logged by exception class and noticed once, instead of escaping the handler.
 */
@Suppress("unused")
class ShowDocumentation(private val browser: BrowserLauncher = BrowserLauncher()) : AbstractHandler() {
  val logger = logger<ShowDocumentation>()

  @Throws(ExecutionException::class)
  override fun execute(event: ExecutionEvent): Any? {
    logger.info("Showing GitLab for Eclipse settings.")
    browser.open(URL)
    return null
  }

  companion object {
    const val URL = "https://docs.gitlab.com/editor_extensions/eclipse/"
  }
}
