package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.commands.ExecutionException

@Suppress("unused")
class DisableAllCodeSuggestions : AbstractHandler() {
  val logger = logger<DisableAllCodeSuggestions>()

  @Throws(ExecutionException::class)
  override fun execute(event: ExecutionEvent): Any? {
    logger.info("Disabling all code suggestions.")
    return null
  }
}
