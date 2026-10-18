package com.gitlab.eclipse.mcp

import com.gitlab.eclipse.utils.logger
import org.eclipse.core.resources.IFile
import org.eclipse.core.resources.IResource
import org.eclipse.core.resources.IWorkspace
import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.core.resources.WorkspaceJob
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.OperationCanceledException
import org.eclipse.core.runtime.Status
import java.nio.file.Path

/** What [McpWorkspaceConfigWriter] did, for the handler to act on once the job is done. */
sealed interface McpWorkspaceConfigOutcome {
  /** The config exists at [configFile]; [file] is its workspace handle. */
  data class Ready(
    val file: IFile,
    val configFile: Path,
    val projectCount: Int,
    val projectName: String,
  ) : McpWorkspaceConfigOutcome

  /** Nothing was written: [resolution] is the reason (anything but a Target). */
  data class Refused(val resolution: McpWorkspaceConfigResolution) : McpWorkspaceConfigOutcome

  /** Creating or refreshing the config in [projectName] failed; the log has the exception class. */
  data class Failed(val projectName: String) : McpWorkspaceConfigOutcome

  /** The job never ran (cancelled while queued) or was cancelled: nothing to report. */
  data object Cancelled : McpWorkspaceConfigOutcome
}

/**
 * The workspace half of "Open MCP Workspace Configuration": one `WorkspaceJob` under the workspace
 * root rule that resolves the target ([McpWorkspaceConfigTarget]), creates the config if it is
 * missing ([McpConfigService.ensureConfigFile]: never overwrites) and refreshes `.gitlab` so the
 * file is a workspace resource. It never opens an editor; [McpWorkspaceConfigOutcome.Ready] is for
 * the handler to act on from the UI thread.
 *
 * The target is resolved **here, not on the UI thread**: under the root rule, project creation,
 * deletion, open and close wait for this job, so the first project cannot change between the
 * decision and the write (same reasoning as `DuoTutorialWorkspaceWriter`).
 *
 * [outcome] starts as [McpWorkspaceConfigOutcome.Cancelled]: a job cancelled while queued never
 * reaches [runInWorkspace], and `done` still fires.
 */
class McpWorkspaceConfigWriter(
  private val workspace: IWorkspace = ResourcesPlugin.getWorkspace(),
  private val target: McpWorkspaceConfigTarget = McpWorkspaceConfigTarget(),
  private val service: McpConfigService = McpConfigService(),
) : WorkspaceJob(JOB_NAME) {
  private val logger by lazy { logger<McpWorkspaceConfigWriter>() }

  @Volatile
  var outcome: McpWorkspaceConfigOutcome = McpWorkspaceConfigOutcome.Cancelled
    private set

  init {
    rule = workspace.root
    isUser = true
  }

  override fun runInWorkspace(monitor: IProgressMonitor?): IStatus {
    val projects = workspace.root.projects
    val resolution = target.resolve(projects)
    if (resolution !is McpWorkspaceConfigResolution.Target) {
      outcome = McpWorkspaceConfigOutcome.Refused(resolution)
      return Status.OK_STATUS
    }
    val project = resolution.project
    outcome = try {
      service.ensureConfigFile(resolution.configFile)
      project.getFolder(GITLAB_FOLDER).refreshLocal(IResource.DEPTH_INFINITE, monitor)
      McpWorkspaceConfigOutcome.Ready(project.getFile(CONFIG_FILE), resolution.configFile, projects.size, project.name)
    } catch (_: OperationCanceledException) {
      logger.info("$JOB_NAME: cancelled")
      outcome = McpWorkspaceConfigOutcome.Cancelled
      return Status.CANCEL_STATUS
    } catch (e: Exception) {
      // Class name only: the messages carry the local path (ensureConfigFile puts it in on purpose).
      logger.error("$JOB_NAME: could not create the config in project '${project.name}': ${e.javaClass.name}")
      McpWorkspaceConfigOutcome.Failed(project.name)
    }
    return Status.OK_STATUS
  }

  private companion object {
    const val JOB_NAME = "Open MCP Workspace Configuration"
    const val GITLAB_FOLDER = ".gitlab"
    const val CONFIG_FILE = ".gitlab/duo/mcp.json"
  }
}
