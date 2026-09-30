package com.gitlab.eclipse.chat.terminal

import com.gitlab.eclipse.chat.utils.openDuoChatWindowWithClassicPrompt
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.handlers.HandlerUtil

/**
 * `gitlab-eclipse-plugin.commands.ExplainTerminalOutput` (the reference extension's
 * `gl.webview.explainSelectedTerminalOutput`), contributed to the context menus of both Terminal
 * view generations and of the Console pages. A thin shell over [ExplainTerminalOutputCommand].
 *
 * Runs on the UI thread, which reading the selection requires. Only the menu selection is used: it
 * is the selection of the view whose menu was opened, whereas the current selection can belong to
 * another part or be stale.
 */
@Suppress("unused")
class ExplainTerminalOutputHandler : AbstractHandler() {
  private val logger = logger<ExplainTerminalOutputHandler>()

  override fun execute(event: ExecutionEvent): Any? {
    ExplainTerminalOutputCommand(
      isAvailable = { service<TerminalContextSourceProvider>().isEnabled },
      send = ::openDuoChatWindowWithClassicPrompt,
      notify = NotificationUtils::show,
      log = { logger.warn(it) },
    ).run(HandlerUtil.getActiveMenuSelection(event))
    return null
  }
}
