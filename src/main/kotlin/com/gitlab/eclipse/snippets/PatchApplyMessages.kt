package com.gitlab.eclipse.snippets

/**
 * Turns a [PatchApplyOutcome] into what the user is told (design §13 / §16).
 *
 * Separate from the handler so it can be tested headless, and because the rule it enforces is easy
 * to break by accident: a message carries counts and never a path, a URI or a JGit exception
 * message (A9). Nothing here interpolates anything but an integer.
 */
object PatchApplyMessages {
  private const val GENERIC_FAILURE = "GitLab: Could not apply the patch. See the Error Log."

  fun of(outcome: PatchApplyOutcome): String = counted(outcome) ?: constant(outcome)

  /** The outcomes that quantify what happened. */
  private fun counted(outcome: PatchApplyOutcome): String? = when (outcome) {
    is PatchApplyOutcome.Applied -> buildString {
      append("GitLab: Applied the patch to ${outcome.pathCount} file(s).")
      if (outcome.prunedBackups > 0) {
        append(" Removed ${outcome.prunedBackups} expired patch backup(s).")
      }
    }
    is PatchApplyOutcome.RolledBack -> buildString {
      append("GitLab: The patch failed and was rolled back (${outcome.restored} file(s) restored).")
      if (outcome.notRestored > 0) {
        append(" ${outcome.notRestored} file(s) were changed outside Eclipse and were left as they are.")
        append(" Their original contents are in the patch backup area.")
      }
    }
    is PatchApplyOutcome.StagedChanges ->
      "GitLab: ${outcome.count} file(s) have staged changes. Commit or unstage them first."
    is PatchApplyOutcome.PatchRejected ->
      "GitLab: The patch does not apply to this working tree (${outcome.errorCount} conflict(s))."
    else -> null
  }

  private fun constant(outcome: PatchApplyOutcome): String = when (outcome) {
    PatchApplyOutcome.Busy ->
      "GitLab: Another git operation is running on this repository. Try again when it finishes."
    PatchApplyOutcome.NoHead -> "GitLab: The repository has no commit to apply a patch to."
    PatchApplyOutcome.Empty -> "GitLab: That snippet is not a patch, or it changes nothing."
    PatchApplyOutcome.BinaryNotSupported ->
      "GitLab: The patch contains a binary change, which cannot be applied."
    PatchApplyOutcome.GitlinkNotSupported ->
      "GitLab: The patch changes a submodule, which is not supported."
    PatchApplyOutcome.TooLarge ->
      "GitLab: The patch is too large to back up safely, so it was not applied."
    PatchApplyOutcome.BackupUnavailable ->
      "GitLab: The files this patch would overwrite could not be backed up. Nothing was changed."
    PatchApplyOutcome.IndexConflicted ->
      "GitLab: The files were written but the index could not be updated, because it changed " +
        "underneath. Review with git status; the originals are in the patch backup area."
    // PatchApplyOutcome.Failed, and anything a later change adds without a message of its own:
    // a generic line is the safe default, since the alternative is leaking an exception message.
    else -> GENERIC_FAILURE
  }
}
