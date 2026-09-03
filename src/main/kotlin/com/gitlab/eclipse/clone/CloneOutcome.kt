package com.gitlab.eclipse.clone

import java.io.File

/**
 * Where the repository at the destination came from. Needed because the two paths can make
 * different claims: an adopted repository was never cloned by this run and its completeness is
 * unknown, so no message on that path may say the clone finished.
 */
enum class RepositorySource { CLONED_NOW, ADOPTED_EXISTING }

/** Why an on-disk repository was not imported as an Eclipse project. */
enum class ImportSkipReason { NAME_TAKEN, NO_WORKSPACE, IMPORT_FAILED, LOCATION_REJECTED }

/**
 * The outcome of one run of the shared clone-and-import flow — F5 (`cloneWiki`) and F7
 * (`openRepository`) alike — for the handler to notify the user about.
 */
sealed interface CloneOutcome {
  data class Cloned(val directory: File) : CloneOutcome
  data class Imported(
    val directory: File,
    val projectName: String,
    val source: RepositorySource,
  ) : CloneOutcome
  data class ImportSkipped(
    val directory: File,
    val reason: ImportSkipReason,
    val source: RepositorySource,
    /**
     * The name to render in wording that needs one. [ImportSkipReason.NAME_TAKEN] names the
     * project holding the name, and [ImportSkipReason.LOCATION_REJECTED] tells the user to
     * rename the folder to the `.project` name — the folder name would be exactly the wrong
     * value there, because rejection happens only when the two differ. Falls back to the
     * folder name on the paths where no project description could be read.
     */
    val projectName: String,
    /**
     * Non-null = a closed, orphaned project registration by this name remains in the workspace
     * (consent to release it was declined, or a delete of the registration failed). The caller
     * must show [CloneMessages.orphanCleanupInstructions] for this name; on the declined-consent
     * path that message stands alone — the [reason]'s own wording is not also shown.
     */
    val leftoverProjectName: String? = null,
  ) : CloneOutcome
  data object Cancelled : CloneOutcome

  /** [type] is the exception's type name only; messages can carry urls (A9). */
  data class Failed(val type: String, val directory: File) : CloneOutcome
}
