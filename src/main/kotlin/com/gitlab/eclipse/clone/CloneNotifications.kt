package com.gitlab.eclipse.clone

import java.io.File

/**
 * Composes the one notification a clone-and-import run ends with.
 *
 * Pure and Eclipse-free like [CloneMessages] — deliberately, so the composition rules (which
 * wording stands alone, what gets combined into a single dialog) are pinned by a headless spec
 * (`CloneNotificationsTest`) instead of being verifiable only by clicking through a real
 * workbench. The handler owns *showing* the string; this object only decides what it says.
 */
internal object CloneNotifications {

  /** Maps the import's outcome to its notification; every string comes from [CloneMessages]. */
  fun importNotification(outcome: CloneOutcome, source: RepositorySource, destination: File): String =
    when (outcome) {
      is CloneOutcome.Imported -> CloneMessages.imported(outcome.source, outcome.projectName)
      is CloneOutcome.ImportSkipped -> importSkippedNotification(outcome)
      // The importer's contract returns Imported or ImportSkipped; anything else is a broken
      // contract, reported as a failed import rather than silently dropped.
      is CloneOutcome.Cloned, CloneOutcome.Cancelled, is CloneOutcome.Failed ->
        CloneMessages.importSkipped(ImportSkipReason.IMPORT_FAILED, source, destination.name)
    }

  /**
   * One dialog, never two in sequence. A non-null [CloneOutcome.ImportSkipped.leftoverProjectName]
   * means a closed orphan registration remains: on the declined-consent path (NAME_TAKEN) the
   * cleanup instructions stand ALONE — the reason's own wording is not also shown — while after
   * a failed compensation (IMPORT_FAILED) the user needs both facts, composed into one message.
   */
  private fun importSkippedNotification(outcome: CloneOutcome.ImportSkipped): String {
    val leftover = outcome.leftoverProjectName
      ?: return CloneMessages.importSkipped(outcome.reason, outcome.source, outcome.projectName)
    return when (outcome.reason) {
      ImportSkipReason.NAME_TAKEN -> CloneMessages.orphanCleanupInstructions(leftover)
      else ->
        CloneMessages.importSkipped(outcome.reason, outcome.source, outcome.projectName) +
          "\n\n" + CloneMessages.orphanCleanupInstructions(leftover)
    }
  }
}
