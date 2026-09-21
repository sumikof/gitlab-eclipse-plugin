package com.gitlab.eclipse.diagnostics.handlers

import com.gitlab.eclipse.diagnostics.DiagnosticsService
import com.gitlab.eclipse.diagnostics.openDiagnosticsFile
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent

/**
 * `gl.showDiagnostics` **and** `gl.showDiagnosticsFromSidePanel` — opens the diagnostics report
 * (design §9.3, F2 and F3).
 *
 * **One handler, two command ids.** That is not a shortcut: the reference extension binds both ids
 * to the same function (`src/common/main.ts:49,53`) and differs only in where each is contributed —
 * the second appears in the chat view's title bar. Giving Eclipse two handlers would invent a
 * difference the reference does not have, and let the two drift.
 *
 * Collection touches no network (N1) and the report is small, so this stays on the calling thread.
 */
@Suppress("unused")
class ShowDiagnosticsHandler : AbstractHandler() {
  private val logger by lazy { logger<ShowDiagnosticsHandler>() }
  private val diagnostics = DiagnosticsService()

  override fun execute(event: ExecutionEvent): Any? {
    try {
      val path = diagnostics.materialize(DiagnosticsService.REPORT_FILE, diagnostics.report())
      openDiagnosticsFile(path)
    } catch (e: Exception) {
      logger.warn("Could not show diagnostics: ${e::class.simpleName}")
      NotificationUtils.show(COULD_NOT_SHOW)
    }
    return null
  }

  private companion object {
    const val COULD_NOT_SHOW = "Could not open the GitLab diagnostics report."
  }
}
