package com.gitlab.eclipse.clone

import com.gitlab.eclipse.utils.logger
import org.eclipse.core.resources.IProject
import org.eclipse.core.resources.IProjectDescription
import org.eclipse.core.resources.IWorkspace
import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.core.runtime.Path
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Registers a repository on disk as an open Eclipse project ([CloneOutcome.Imported]) or explains
 * why it did not ([CloneOutcome.ImportSkipped]).
 *
 * Runs off the UI thread and never touches SWT. The only user interaction is [confirm] — a
 * yes/no question, implemented by the handler with a hop to the UI thread — used solely for the
 * orphan-registration consent ([CloneMessages.orphanConsent]), because the flow blocks on the
 * answer. Everything else is carried by the returned [CloneOutcome]: in particular, when
 * [CloneOutcome.ImportSkipped.leftoverProjectName] is non-null, a closed orphan registration
 * remains and the caller must show [CloneMessages.orphanCleanupInstructions] for that name (on
 * the declined-consent path that message stands alone, not alongside the reason's wording).
 *
 * When this runs, the repository at [import]'s destination already exists — cloned by this run
 * ([RepositorySource.CLONED_NOW]) or found there and adopted ([RepositorySource.ADOPTED_EXISTING])
 * — so no path through this class may touch the on-disk contents. The only `delete` calls here
 * are `deleteContent = false`, which removes the workspace *registration* and leaves every file
 * in place.
 */
class ClonedProjectImporter(
  private val confirm: (String) -> Boolean,
) {
  private val logger by lazy { logger<ClonedProjectImporter>() }

  /**
   * Imports the repository at [destination] into the workspace.
   *
   * The order of the checks is deliberate:
   * 1. Resolve the workspace; without a workspace location nothing below is meaningful
   *    ([ImportSkipReason.NO_WORKSPACE]).
   * 2. Read `.project` if present — the *project's* name can differ from the folder name, and
   *    both the location rule and the same-name check below need the project name.
   * 3. Apply the three-way location rule ([ProjectLocationDecider]) before touching anything in
   *    the workspace: `Rejected` returns [ImportSkipReason.LOCATION_REJECTED] without calling
   *    `create` and without disturbing any same-name project. (No abandoned registration of ours
   *    can exist at a rejected destination — a previous run of this flow would have been rejected
   *    there too, before creating anything — so returning here cannot hide the recovery path.)
   * 4. Only then look at a same-name project, and even there decide "is this our abandoned
   *    registration?" BEFORE concluding "the name is taken" — see [releaseOrphanRegistration].
   *    The design numbers the recovery step last but requires it evaluated first: implementing
   *    the steps in their written order makes recovery unreachable, stranding any user whose
   *    Eclipse died mid-import.
   */
  fun import(destination: File, source: RepositorySource): CloneOutcome {
    val workspace = workspaceOrNull()
      ?: return CloneOutcome.ImportSkipped(destination, ImportSkipReason.NO_WORKSPACE, source, destination.name)
    val workspaceRoot = workspace.root.location?.toFile()
      ?: return CloneOutcome.ImportSkipped(destination, ImportSkipReason.NO_WORKSPACE, source, destination.name)
    val description = readDescription(workspace, destination)
      ?: return CloneOutcome.ImportSkipped(destination, ImportSkipReason.IMPORT_FAILED, source, destination.name)
    when (val decision = ProjectLocationDecider.decide(destination, description.name, workspaceRoot)) {
      ProjectLocationDecider.Decision.Rejected ->
        return CloneOutcome.ImportSkipped(destination, ImportSkipReason.LOCATION_REJECTED, source, description.name)
      is ProjectLocationDecider.Decision.SetLocation ->
        description.locationURI = decision.destination.toURI()
      ProjectLocationDecider.Decision.UseDefaultLocation -> Unit
    }
    val existing = workspace.root.getProject(description.name)
    if (existing.exists()) {
      when (releaseOrphanRegistration(existing, destination)) {
        OrphanRelease.RELEASED -> Unit
        OrphanRelease.NOT_OURS ->
          return CloneOutcome.ImportSkipped(destination, ImportSkipReason.NAME_TAKEN, source, description.name)
        OrphanRelease.STILL_REGISTERED ->
          return CloneOutcome.ImportSkipped(
            destination,
            ImportSkipReason.NAME_TAKEN,
            source,
            projectName = description.name,
            leftoverProjectName = existing.name,
          )
      }
    }
    return createAndOpen(workspace, description, destination, source)
  }

  /** What became of a same-name project found before `create`. */
  private enum class OrphanRelease {
    /** It was our abandoned registration; the user consented and it was released — the name is free. */
    RELEASED,

    /** Open, or registered elsewhere: somebody else's project, never touched (C5). */
    NOT_OURS,

    /** Our closed orphan, but it remains registered (consent declined, or the delete failed). */
    STILL_REGISTERED,
  }

  /**
   * `.project` present: read it — its `name` may differ from the folder name. `.project` absent:
   * a fresh description named after the folder; that one can never be `Rejected`, because folder
   * name and project name then coincide by construction.
   *
   * `loadProjectDescription` deliberately does NOT set a location from the file it read. The
   * location must be decided explicitly afterwards (see [ProjectLocationDecider]) — used as-is,
   * the description creates a *different* project at `${workspace}/${projectName}` and the clone
   * silently stays unimported while everything looks successful.
   */
  @Suppress("TooGenericExceptionCaught")
  private fun readDescription(workspace: IWorkspace, destination: File): IProjectDescription? =
    try {
      val dotProject = File(destination, IProjectDescription.DESCRIPTION_FILE_NAME)
      if (dotProject.isFile) {
        workspace.loadProjectDescription(Path(dotProject.absolutePath))
      } else {
        workspace.newProjectDescription(destination.name)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      logger.error("Reading the project description failed: ${e.javaClass.name}")
      null
    }

  /**
   * Decides whether a same-name project is OUR abandoned registration and, with the user's
   * consent, releases it.
   *
   * This is the crash-recovery path for "Eclipse died between `create()` and `open()`". No
   * persistent record is kept, so the candidate is recognized purely from what is observable:
   * the project is CLOSED and its registered location is exactly the current destination. A
   * same-name project that is open, or registered anywhere else, is somebody else's project —
   * it is never touched (C5), and the caller reports [ImportSkipReason.NAME_TAKEN].
   *
   * The release is `delete(deleteContent = false, force = true)`: registration only, the files
   * at the destination stay. Declined consent and a failed delete both leave the registration
   * alone ([OrphanRelease.STILL_REGISTERED]); the caller then sets
   * [CloneOutcome.ImportSkipped.leftoverProjectName] so the notification site shows the manual
   * `Delete` instructions (content checkbox left unchecked) — a bare NAME_TAKEN notification
   * would leave the user with no way out.
   */
  @Suppress("TooGenericExceptionCaught")
  private fun releaseOrphanRegistration(existing: IProject, destination: File): OrphanRelease {
    val registeredLocation = existing.location?.toFile()?.absoluteFile
    val abandonedHere = !existing.isOpen && registeredLocation == destination.absoluteFile
    if (!abandonedHere) return OrphanRelease.NOT_OURS
    if (!confirm(CloneMessages.orphanConsent(existing.name))) return OrphanRelease.STILL_REGISTERED
    return try {
      // delete(deleteContent = false, force = true, monitor): registration only, files stay.
      existing.delete(false, true, null)
      OrphanRelease.RELEASED
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      logger.error("Releasing the abandoned registration failed: ${e.javaClass.name}")
      OrphanRelease.STILL_REGISTERED
    }
  }

  /**
   * `create` then `open`, with the compensation the design requires between them.
   *
   * If `create()` itself fails there is nothing to compensate — no registration was made. If
   * `create()` succeeded but `open()` failed, the half-made registration is removed again (see
   * [deregisterAfterFailedOpen]) so a later retry can succeed. Either way the failure is the
   * import's, not the clone's: the outcome is [ImportSkipReason.IMPORT_FAILED] and the files at
   * the destination are left untouched.
   */
  @Suppress("TooGenericExceptionCaught")
  private fun createAndOpen(
    workspace: IWorkspace,
    description: IProjectDescription,
    destination: File,
    source: RepositorySource,
  ): CloneOutcome {
    val project = workspace.root.getProject(description.name)
    try {
      project.create(description, null)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      logger.error("Project creation failed: ${e.javaClass.name}")
      return CloneOutcome.ImportSkipped(destination, ImportSkipReason.IMPORT_FAILED, source, description.name)
    }
    try {
      project.open(null)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      logger.error("Opening the created project failed: ${e.javaClass.name}")
      val leftover = if (deregisterAfterFailedOpen(project)) null else project.name
      return CloneOutcome.ImportSkipped(
        destination,
        ImportSkipReason.IMPORT_FAILED,
        source,
        projectName = description.name,
        leftoverProjectName = leftover,
      )
    }
    return CloneOutcome.Imported(destination, description.name, source)
  }

  /**
   * Compensation: remove the registration `create()` just made — never the files
   * (`deleteContent = false`). Returns `false` when even this fails: a closed project then
   * lingers in the workspace, and the caller sets
   * [CloneOutcome.ImportSkipped.leftoverProjectName] so the user is told how to remove it
   * manually without deleting the clone's contents.
   */
  @Suppress("TooGenericExceptionCaught")
  private fun deregisterAfterFailedOpen(project: IProject): Boolean =
    try {
      // delete(deleteContent = false, force = true, monitor): registration only, files stay.
      project.delete(false, true, null)
      true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      logger.error("Compensating delete failed: ${e.javaClass.name}")
      false
    }

  /** The workspace, or null when the resources bundle is unavailable (headless / not started). */
  @Suppress("TooGenericExceptionCaught")
  private fun workspaceOrNull(): IWorkspace? =
    try {
      ResourcesPlugin.getWorkspace()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      logger.error("Workspace unavailable: ${e.javaClass.name}")
      null
    }
}
