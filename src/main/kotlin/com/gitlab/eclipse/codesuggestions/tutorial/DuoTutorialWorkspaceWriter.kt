package com.gitlab.eclipse.codesuggestions.tutorial

import com.gitlab.eclipse.utils.logger
import org.eclipse.core.resources.IProject
import org.eclipse.core.resources.IResource
import org.eclipse.core.resources.IWorkspace
import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.core.resources.WorkspaceJob
import org.eclipse.core.runtime.CoreException
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.NullProgressMonitor
import org.eclipse.core.runtime.OperationCanceledException
import org.eclipse.core.runtime.Platform
import org.eclipse.core.runtime.Status
import org.osgi.framework.FrameworkUtil
import java.nio.file.Path
import java.util.UUID

/**
 * The workspace half of the Tutorial command (design §8.2, §9.2): one `WorkspaceJob` under the
 * workspace root rule that re-reads the state, asks [DuoTutorialProjectPlanner] what to do, and
 * carries the steps out — creating the project in a fresh, unguessable directory under this
 * plugin's state location, recording ownership, and creating the file. It never opens an editor;
 * the `OpenEditor` step becomes [WriterOutcome.Ready] for the handler to act on from the UI thread.
 *
 * **Why everything is decided in here and not in the handler**: a second run started while the
 * first is between `create` and the property write would, judged on the UI thread, see a project
 * that exists but is not owned and refuse wrongly. Under the root rule the second job waits for the
 * first to finish, then re-reads.
 *
 * **Creation order and compensation** (§9.2): ① id → ② reserve `<state>/duo-tutorial/<id>` with
 * `createDirectory` (fails if present; compensation is armed from here) → ③ `create` with that
 * location → ④ read `locationURI` (null on an unresolved handle, hence after create) → ⑤ record id
 * and location, `save()` → ⑥ `open` → ⑦ persistent property (cannot be set on a closed project;
 * disarms compensation: the project is owned now) → ⑧ create the file. Any failure or cancel
 * between ② and ⑦ removes the registration (content kept, we delete the directory ourselves) and
 * the reserved directory, with a [NullProgressMonitor] — the job's own monitor may be cancelled.
 * A failure at ⑧ leaves the owned project; the next run lands on the "file missing" row.
 *
 * [outcome] starts as [WriterOutcome.Cancelled]: a job cancelled while queued behind another
 * root-rule job never reaches [runInWorkspace], and `done` still fires.
 *
 * The default location `<workspace>/GitLab Duo Tutorial` is never read or written (R9).
 */
class DuoTutorialWorkspaceWriter(
  private val workspace: IWorkspace = ResourcesPlugin.getWorkspace(),
  private val ownership: DuoTutorialOwnership = DuoTutorialOwnership(),
  private val tutorialsDirectory: () -> Path = ::defaultTutorialsDirectory,
  private val fileSystem: DuoTutorialFileSystem = DuoTutorialFileSystem.Default,
  private val newId: () -> String = { UUID.randomUUID().toString() },
) : WorkspaceJob(JOB_NAME) {
  private val logger by lazy { logger<DuoTutorialWorkspaceWriter>() }

  @Volatile
  var outcome: WriterOutcome = WriterOutcome.Cancelled
    private set

  init {
    rule = workspace.root
    isUser = true
  }

  override fun runInWorkspace(monitor: IProgressMonitor?): IStatus {
    val progress = monitor ?: NullProgressMonitor()
    logger.info("$JOB_NAME job started")
    val project = workspace.root.getProject(DuoTutorialContent.PROJECT_NAME)
    val status = when (val plan = DuoTutorialProjectPlanner.plan(readState(project))) {
      is DuoTutorialPlan.Refuse -> {
        outcome = WriterOutcome.Refused(plan.reason)
        Status.OK_STATUS
      }
      is DuoTutorialPlan.Actions -> execute(project, plan.steps, progress)
    }
    logger.info("$JOB_NAME job finished: ${outcome::class.simpleName}")
    return status
  }

  /** The facts for the planner. A closed project's ownership is not read (persistent property). */
  private fun readState(project: IProject): DuoTutorialState = when {
    !project.exists() -> DuoTutorialState.NoProject
    !project.isOpen -> DuoTutorialState.ProjectClosed
    else -> DuoTutorialState.ProjectOpen(
      owned = ownership.isOwned(project),
      fileExists = project.getFile(DuoTutorialContent.FILE_NAME).exists(),
    )
  }

  private fun execute(project: IProject, steps: List<DuoTutorialAction>, monitor: IProgressMonitor): IStatus {
    logger.info("$JOB_NAME plan: ${steps.joinToString { it::class.simpleName.orEmpty() }}")
    val run = Run(project, monitor)
    return try {
      for (step in steps) {
        run.checkCancelled()
        run.perform(step)
      }
      Status.OK_STATUS
    } catch (_: OperationCanceledException) {
      finish(run, WriterOutcome.Cancelled)
    } catch (e: Exception) {
      // Class name only (A16): the message could carry the location.
      logger.error("$JOB_NAME step failed: ${e.javaClass.name}")
      finish(run, WriterOutcome.Failed)
    }
  }

  /** Sets [result], compensates if a reservation is still armed, and picks the platform status. */
  private fun finish(run: Run, result: WriterOutcome): IStatus {
    outcome = result
    val normal = if (result is WriterOutcome.Cancelled) Status.CANCEL_STATUS else Status.OK_STATUS
    val reservation = run.reservation ?: return normal
    return if (compensate(run.project, reservation)) normal else Status.error(COMPENSATION_FAILED)
  }

  /**
   * Removes what this run made before ownership was established. Registration first (content
   * untouched: the directory is ours and goes next), then the reserved directory with everything in
   * it. Both are attempted even if the first fails; false when either did.
   */
  private fun compensate(project: IProject, reservation: Reservation): Boolean {
    var clean = true
    try {
      if (project.exists()) {
        project.delete(IResource.NEVER_DELETE_PROJECT_CONTENT or IResource.FORCE, NullProgressMonitor())
      }
    } catch (e: Exception) {
      logger.error("$JOB_NAME compensation could not remove the project registration: ${e.javaClass.name}")
      clean = false
    }
    try {
      fileSystem.deleteRecursively(reservation.directory)
    } catch (e: Exception) {
      logger.error("$JOB_NAME compensation could not remove the reserved directory: ${e.javaClass.name}")
      clean = false
    }
    return clean
  }

  private class Reservation(val id: String, val directory: Path)

  /** One execution: the project handle, the job's monitor, and the reservation compensation covers. */
  private inner class Run(val project: IProject, private val monitor: IProgressMonitor) {
    var reservation: Reservation? = null

    fun checkCancelled() {
      if (monitor.isCanceled) throw OperationCanceledException()
    }

    fun perform(step: DuoTutorialAction) {
      when (step) {
        DuoTutorialAction.CreateProject -> createProject()
        DuoTutorialAction.OpenProject -> openProject()
        DuoTutorialAction.CreateFile -> createFile()
        DuoTutorialAction.OpenEditor -> outcome = WriterOutcome.Ready(project.getFile(DuoTutorialContent.FILE_NAME))
      }
    }

    private fun createProject() {
      val id = newId()
      val parent = tutorialsDirectory()
      val directory = parent.resolve(id)
      fileSystem.createDirectories(parent)
      fileSystem.createDirectory(directory) // reservation; a failure here has nothing to compensate
      reservation = Reservation(id, directory)
      checkCancelled()
      val description = workspace.newProjectDescription(DuoTutorialContent.PROJECT_NAME)
      description.locationURI = directory.toUri()
      project.create(description, monitor)
      checkCancelled()
      val location = project.locationURI ?: throw LocationUnavailableException()
      if (!ownership.record(id, location.toString())) throw RecordNotSavedException()
      checkCancelled()
    }

    private fun openProject() {
      val current = reservation ?: error("OpenProject without a CreateProject")
      project.open(monitor)
      checkCancelled()
      project.setPersistentProperty(DuoTutorialOwnership.PROPERTY_ID, current.id)
      reservation = null // owned from here on: nothing more to compensate
    }

    /**
     * `force = false`: a same-named file already on disk that Eclipse has not seen yet makes this
     * throw; then a refresh shows it, and the "file exists" row applies — never an overwrite (R9).
     */
    private fun createFile() {
      val file = project.getFile(DuoTutorialContent.FILE_NAME)
      try {
        file.create(DuoTutorialContent.TEXT.byteInputStream(), false, monitor)
      } catch (e: CoreException) {
        file.refreshLocal(IResource.DEPTH_ZERO, NullProgressMonitor())
        if (!file.exists()) throw e
        logger.info("$JOB_NAME file already existed on disk; kept as is")
      }
    }
  }

  private class LocationUnavailableException : RuntimeException("project location unavailable after create")

  private class RecordNotSavedException : RuntimeException("ownership record could not be saved")

  companion object {
    const val JOB_NAME: String = "GitLab Duo Tutorial"
    private const val TUTORIALS_DIRECTORY = "duo-tutorial"
    private const val COMPENSATION_FAILED =
      "The GitLab Duo Tutorial project could not be created, and cleaning up after the failure also failed. " +
        "A 'GitLab Duo Tutorial' project or a directory under the plugin's state location may be left behind."

    /** `<workspace>/.metadata/.plugins/<bundle>/duo-tutorial` — same lifetime as the InstanceScope record. */
    private fun defaultTutorialsDirectory(): Path {
      val bundle = FrameworkUtil.getBundle(DuoTutorialWorkspaceWriter::class.java)
      return Platform.getStateLocation(bundle).toFile().toPath().resolve(TUTORIALS_DIRECTORY)
    }
  }
}
