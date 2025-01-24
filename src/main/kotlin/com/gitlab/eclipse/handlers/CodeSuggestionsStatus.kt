package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.commands.ExecutionException

@Suppress("unused")
class CodeSuggestionsStatus : AbstractHandler() {
  val logger = logger<CodeSuggestionsStatus>()

  @Throws(ExecutionException::class)
  override fun execute(event: ExecutionEvent): Any? {
    logger.info("Showing code suggestions status.")
    return null
  }
}
