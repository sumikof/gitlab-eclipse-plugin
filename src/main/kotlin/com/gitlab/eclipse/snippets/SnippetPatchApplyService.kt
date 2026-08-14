package com.gitlab.eclipse.snippets

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.mergerequests.GitOperationGuard
import com.gitlab.eclipse.utils.logger
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File

/** Every way a patch application can end. Exactly one is returned; the service never throws. */
sealed interface PatchApplyOutcome {
  /** [pathCount] paths were written and staged. [prunedBackups] expired backups were removed. */
  data class Applied(val pathCount: Int, val prunedBackups: Int) : PatchApplyOutcome

  /** Another guarded git operation holds this repository; nothing was done. */
  data object Busy : PatchApplyOutcome

  data object NoHead : PatchApplyOutcome

  /** The text is not a patch, or applying it would change nothing. */
  data object Empty : PatchApplyOutcome

  data object BinaryNotSupported : PatchApplyOutcome

  data object GitlinkNotSupported : PatchApplyOutcome

  /** A19: [count] target paths carry staged changes, so nothing was written. */
  data class StagedChanges(val count: Int) : PatchApplyOutcome

  /** The patch does not apply to HEAD. [errorCount] is a count only — the messages quote paths. */
  data class PatchRejected(val errorCount: Int) : PatchApplyOutcome

  /** The post-images are too large to back up, so the apply never started. */
  data object TooLarge : PatchApplyOutcome

  /** A21 (b): room could not be made without deleting a still-retained backup. Nothing was done. */
  data object BackupUnavailable : PatchApplyOutcome

  /** A11: the write failed part way and was rolled back. [notRestored] were left as found. */
  data class RolledBack(val restored: Int, val notRestored: Int) : PatchApplyOutcome

  /** The files landed but the index could not be updated; the working tree is NOT rolled back. */
  data object IndexConflicted : PatchApplyOutcome

  /** [type] is the exception's class name — never its message, which would quote paths (A9). */
  data class Failed(val type: String) : PatchApplyOutcome
}

/**
 * Applies a patch snippet to a repository's working tree (design F4).
 *
 * Runs the whole sequence inside [GitOperationGuard] so it is serialized against this plugin's
 * other git operations. That guard is NOT the protection against editors: the caller is expected
 * to hold an [org.eclipse.core.runtime.jobs.ISchedulingRule] over the apply, the rollback and the
 * refresh, which is what keeps Eclipse's own saves out of the window (design §9.4 / §15.2).
 *
 * Nothing touches the filesystem until the plan is known to be applicable, so every rejection
 * before that point leaves the repository byte for byte as it was (A15).
 *
 * Blocking git and file I/O — call from a background thread. Never throws.
 */
class SnippetPatchApplyService(
  private val guard: GitOperationGuard = service(),
  private val quarantine: PatchQuarantine = PatchQuarantine(),
) {
  private val logger by lazy { logger<SnippetPatchApplyService>() }

  fun apply(gitDir: File, workTree: File, patchText: String): PatchApplyOutcome =
    try {
      guard.withRepo(gitDir.path) { applyGuarded(gitDir, workTree, patchText) } ?: PatchApplyOutcome.Busy
    } catch (e: Exception) {
      // Type only: JGit messages carry remote URLs and paths (design §13 / A9).
      logger.error("Patch application failed: ${e.javaClass.name}")
      PatchApplyOutcome.Failed(e.javaClass.name)
    }

  private fun applyGuarded(gitDir: File, workTree: File, patchText: String): PatchApplyOutcome =
    FileRepositoryBuilder().setGitDir(gitDir).setMustExist(true).build().use { repo ->
      val plan = PatchApplyPlanner().plan(repo, patchText)
      if (plan !is PatchPlan.Ok) return rejected(plan)

      // Sized from what is on disk now: that is what has to fit in the backup area.
      val estimate = plan.changes.sumOf { File(workTree, it.path).length() }
      val opened = quarantine.open(estimate)
      if (opened !is PatchQuarantine.Opened.Ok) return PatchApplyOutcome.BackupUnavailable

      when (val written = WorkTreePatchWriter().write(workTree, plan.changes, opened.session)) {
        is WriteResult.RolledBack -> {
          opened.session.complete()
          PatchApplyOutcome.RolledBack(written.restored, written.notRestored)
        }
        is WriteResult.Ok -> {
          val updated = PatchIndexUpdater().update(repo, workTree, plan.changes, plan.headTreeId)
          opened.session.complete()
          // Deliberately NOT rolled back on a conflict: reaching here means an external `git add`
          // landed after our writes, and undoing them would both overwrite that change and invent
          // a state that is neither before nor after. The pre-images stay in the backup area.
          if (updated == IndexUpdateResult.Ok) {
            PatchApplyOutcome.Applied(plan.changes.size, opened.prunedCount)
          } else {
            PatchApplyOutcome.IndexConflicted
          }
        }
      }
    }

  private fun rejected(plan: PatchPlan): PatchApplyOutcome = when (plan) {
    is PatchPlan.Ok -> error("An applicable plan is not a rejection.")
    PatchPlan.NoHead -> PatchApplyOutcome.NoHead
    PatchPlan.Empty -> PatchApplyOutcome.Empty
    PatchPlan.BinaryNotSupported -> PatchApplyOutcome.BinaryNotSupported
    PatchPlan.GitlinkNotSupported -> PatchApplyOutcome.GitlinkNotSupported
    is PatchPlan.Malformed -> PatchApplyOutcome.Empty
    is PatchPlan.ApplyFailed -> PatchApplyOutcome.PatchRejected(plan.errorCount)
    is PatchPlan.StagedChanges -> PatchApplyOutcome.StagedChanges(plan.count)
    is PatchPlan.TooLarge -> PatchApplyOutcome.TooLarge
  }
}
