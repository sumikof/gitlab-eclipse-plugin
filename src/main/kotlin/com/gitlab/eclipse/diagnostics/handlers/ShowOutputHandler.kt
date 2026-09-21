package com.gitlab.eclipse.diagnostics.handlers

import com.gitlab.eclipse.diagnostics.DiagnosticsService
import com.gitlab.eclipse.diagnostics.openDiagnosticsFile
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent

/**
 * `gl.showOutput` — shows this plugin's own log (design §9.2, F1).
 *
 * The reference extension reveals a dedicated `GitLab` output channel. Eclipse's nearest equivalent
 * is `org.eclipse.ui.console`, which this bundle does not depend on and which the phase constraints
 * make expensive to add; writing the retained log into the plugin state directory and opening it is
 * symmetric with how `language_server.log` already works, and reuses the editor-opening pattern
 * this codebase already has (design §6.2).
 *
 * The write is small — the ring buffer is capped — so it stays on the calling thread. Only the
 * export, which reads a file that can reach 20 MB, needs a job.
 */
@Suppress("unused")
class ShowOutputHandler : AbstractHandler() {
  private val logger by lazy { logger<ShowOutputHandler>() }
  private val diagnostics = DiagnosticsService()

  override fun execute(event: ExecutionEvent): Any? {
    try {
      val path = diagnostics.materialize(
        DiagnosticsService.EXTENSION_LOG_FILE,
        diagnostics.extensionLogs(),
      )
      openDiagnosticsFile(path)
    } catch (e: Exception) {
      // Only the class name: the message can carry the state directory path, which names the user.
      logger.warn("Could not show the extension logs: ${e::class.simpleName}")
      NotificationUtils.show(COULD_NOT_SHOW)
    }
    return null
  }

  private companion object {
    const val COULD_NOT_SHOW = "Could not open the GitLab extension logs."
  }
}
