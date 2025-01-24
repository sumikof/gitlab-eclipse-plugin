package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.commands.ExecutionException
import org.eclipse.ui.PlatformUI
import java.net.URI

@Suppress("unused")
class ShowDocumentation : AbstractHandler() {
  val logger = logger<ShowDocumentation>()

  @Throws(ExecutionException::class)
  override fun execute(event: ExecutionEvent): Any? {
    logger.info("Showing GitLab for Eclipse settings.")
    PlatformUI.getWorkbench().browserSupport.externalBrowser.openURL(
      URI.create("https://docs.gitlab.com/ee/editor_extensions/eclipse/").toURL()
    )
    return null
  }
}
