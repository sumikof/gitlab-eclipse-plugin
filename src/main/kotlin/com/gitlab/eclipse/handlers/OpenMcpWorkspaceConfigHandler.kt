package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.mcp.McpConfigMessages
import com.gitlab.eclipse.mcp.McpWorkspaceConfigOpener
import com.gitlab.eclipse.mcp.McpWorkspaceConfigOutcome
import com.gitlab.eclipse.mcp.McpWorkspaceConfigWriter
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.runtime.jobs.IJobChangeEvent
import org.eclipse.core.runtime.jobs.JobChangeAdapter
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.handlers.HandlerUtil

/**
 * "Open MCP Workspace Configuration": creates (if missing) and opens `.gitlab/duo/mcp.json` in the
 * first project of the workspace — the first workspace folder the language server is sent, which is
 * where it reads the workspace-scoped MCP config (see `McpWorkspaceConfigTarget`).
 *
 * On the UI thread this only schedules [McpWorkspaceConfigWriter]; the target is decided and the
 * file written inside the job. Its `done` reports the outcome: `Ready` goes to
 * [McpWorkspaceConfigOpener], refusals and `Failed` become a notice, `Cancelled` nothing.
 */
@Suppress("unused")
class OpenMcpWorkspaceConfigHandler(
  private val activeWindow: (ExecutionEvent) -> IWorkbenchWindow? = { HandlerUtil.getActiveWorkbenchWindow(it) },
  private val newWriter: () -> McpWorkspaceConfigWriter = { McpWorkspaceConfigWriter() },
  private val schedule: (McpWorkspaceConfigWriter, (McpWorkspaceConfigOutcome) -> Unit) -> Unit =
    ::scheduleAndReport,
  private val opener: McpWorkspaceConfigOpener = McpWorkspaceConfigOpener(),
  private val notify: (String) -> Unit = { NotificationUtils.show(it) },
) : AbstractHandler() {
  private val logger by lazy { logger<OpenMcpWorkspaceConfigHandler>() }

  override fun execute(event: ExecutionEvent): Any? {
    val window = activeWindow(event)
    schedule(newWriter()) { outcome -> report(window, outcome) }
    return null
  }

  /** The job's thread. Everything here either hops to the UI itself or is total. */
  private fun report(window: IWorkbenchWindow?, outcome: McpWorkspaceConfigOutcome) {
    logger.info("MCP workspace configuration: ${outcome::class.simpleName}")
    when (outcome) {
      is McpWorkspaceConfigOutcome.Ready -> opener.open(window, outcome)
      is McpWorkspaceConfigOutcome.Refused -> notify(McpConfigMessages.refusal(outcome.refusal))
      is McpWorkspaceConfigOutcome.Failed -> notify(McpConfigMessages.failed(outcome.projectName))
      McpWorkspaceConfigOutcome.Cancelled -> Unit
    }
  }
}

/**
 * Production scheduling: report [writer]'s outcome when the job is done — which the platform also
 * signals for a job cancelled while still queued, without ever running it; the outcome's initial
 * value covers that.
 */
private fun scheduleAndReport(writer: McpWorkspaceConfigWriter, onDone: (McpWorkspaceConfigOutcome) -> Unit) {
  writer.addJobChangeListener(
    object : JobChangeAdapter() {
      override fun done(event: IJobChangeEvent) = onDone(writer.outcome)
    },
  )
  writer.schedule()
}
