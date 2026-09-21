package com.gitlab.eclipse.diagnostics.handlers

import com.gitlab.eclipse.diagnostics.DiagnosticsArchive
import com.gitlab.eclipse.diagnostics.DiagnosticsFileNaming
import com.gitlab.eclipse.diagnostics.DiagnosticsService
import com.gitlab.eclipse.diagnostics.NonAtomicDestinationException
import com.gitlab.eclipse.diagnostics.promptForArchiveDestination
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.Status
import org.eclipse.core.runtime.jobs.Job
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId

/**
 * `gl.exportDiagnostics` — writes a sanitized ZIP the user can attach to a support request
 * (design §9.4, F4).
 *
 * **The thread split is the point of this class.** A command handler runs on the UI thread, and the
 * work behind this one reads `language_server.log`, which `log4j2.xml` lets grow to 20 MB, runs a
 * dozen regular expressions over it and builds a ZIP in memory. Doing that on the UI thread freezes
 * the workbench for as long as it takes. So:
 *
 * - **UI thread**: the save dialog, and the final notification. Nothing else.
 * - **[Job]**: collect, sanitize, compress, write — cancellable at each step (N5).
 *
 * The dialog comes *first*, before any of the work. Cancelling is the most common outcome of a save
 * dialog, and there is no reason to have read 20 MB by then. The reference builds the archive first
 * and prompts afterwards; the visible behaviour is identical apart from the work not done.
 */
@Suppress("unused")
class ExportDiagnosticsHandler : AbstractHandler() {
  private val logger by lazy { logger<ExportDiagnosticsHandler>() }
  private val diagnostics = DiagnosticsService()

  override fun execute(event: ExecutionEvent): Any? {
    val suggestedName = DiagnosticsFileNaming.archiveName(Instant.now(), ZoneId.systemDefault())
    // Cancelled: say nothing and do nothing. A notification here would be noise about a decision
    // the user just made deliberately (design §14).
    val destination = promptForArchiveDestination(suggestedName) ?: return null
    exportJob(destination).schedule()
    return null
  }

  private fun exportJob(destination: Path): Job = object : Job(JOB_NAME) {
    override fun run(monitor: IProgressMonitor): IStatus {
      monitor.beginTask(JOB_NAME, TOTAL_WORK)
      return try {
        runExport(destination, monitor)
      } catch (e: Exception) {
        failed(e)
      } finally {
        monitor.done()
      }
    }
  }.also { it.setUser(true) }

  private fun runExport(destination: Path, monitor: IProgressMonitor): IStatus {
    monitor.subTask(COLLECTING)
    if (monitor.isCanceled) return Status.CANCEL_STATUS
    val entries = diagnostics.archiveEntries()
    monitor.worked(COLLECT_WORK)

    monitor.subTask(COMPRESSING)
    if (monitor.isCanceled) return Status.CANCEL_STATUS
    val bytes = DiagnosticsArchive.build(entries)
    monitor.worked(COMPRESS_WORK)

    monitor.subTask(WRITING)
    if (monitor.isCanceled) return Status.CANCEL_STATUS
    DiagnosticsArchive.writeAtomically(bytes, destination)
    monitor.worked(WRITE_WORK)

    NotificationUtils.show("$EXPORTED ${destination.fileName}")
    return Status.OK_STATUS
  }

  /**
   * Every failure becomes an OK status carrying a notification, not an error status.
   *
   * An error status makes Eclipse raise its own problem dialog quoting the message, and these
   * messages carry the destination path. The user is told through the same channel as every other
   * outcome, and the log records only the exception's class name (design §17).
   */
  private fun failed(e: Exception): IStatus {
    val message = when (e) {
      is NonAtomicDestinationException -> NOT_ATOMIC
      else -> COULD_NOT_EXPORT
    }
    logger.warn("Diagnostics export failed: ${e::class.simpleName}")
    NotificationUtils.show(message)
    return Status.OK_STATUS
  }

  private companion object {
    const val JOB_NAME = "Exporting GitLab diagnostics"
    const val COLLECTING = "Collecting diagnostics..."
    const val COMPRESSING = "Creating archive..."
    const val WRITING = "Saving..."

    const val TOTAL_WORK = 100
    const val COLLECT_WORK = 60
    const val COMPRESS_WORK = 30
    const val WRITE_WORK = 10

    const val EXPORTED = "GitLab diagnostics exported to"
    const val COULD_NOT_EXPORT = "Could not export the GitLab diagnostics."
    const val NOT_ATOMIC =
      "Could not export: the chosen location does not support safe replacement. " +
        "Choose a location on a local disk."
  }
}
