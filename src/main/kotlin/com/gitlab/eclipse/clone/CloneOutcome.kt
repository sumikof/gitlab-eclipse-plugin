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

/** The outcome of a `cloneWiki` run, for the handler to notify the user about. */
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
  ) : CloneOutcome
  data object Cancelled : CloneOutcome

  /** [type] is the exception's type name only; messages can carry urls (A9). */
  data class Failed(val type: String, val directory: File) : CloneOutcome
}
