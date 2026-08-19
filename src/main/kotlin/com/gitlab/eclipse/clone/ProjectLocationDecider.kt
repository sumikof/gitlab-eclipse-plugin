package com.gitlab.eclipse.clone

import java.io.File

/**
 * Decides what to do about an imported project's location.
 *
 * Two Eclipse rules collide here. `IWorkspace.loadProjectDescription` does NOT set a location
 * from the file it read, so a description used as-is creates the project at
 * `${workspace}/${projectName}` and silently leaves the clone unimported — the location has to be
 * set explicitly. But `LocationValidator` rejects any explicit location directly under the
 * workspace root other than `${workspace}/${projectName}`, so setting it there fails instead.
 *
 * The result is three cases, not two: set it, deliberately leave it unset, or refuse the import.
 */
object ProjectLocationDecider {

  sealed interface Decision {
    /** The destination IS the default location; Eclipse refuses an explicit one. */
    data object UseDefaultLocation : Decision
    data class SetLocation(val destination: File) : Decision

    /** Directly under the workspace root under a name Eclipse will not accept. */
    data object Rejected : Decision
  }

  fun decide(destination: File, projectName: String, workspaceRoot: File): Decision {
    val directlyUnderRoot = destination.absoluteFile.parentFile == workspaceRoot.absoluteFile
    if (!directlyUnderRoot) return Decision.SetLocation(destination)
    return if (destination.name == projectName) Decision.UseDefaultLocation else Decision.Rejected
  }
}
