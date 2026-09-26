package com.gitlab.eclipse.codesuggestions.tutorial

import org.eclipse.core.resources.IFile

/**
 * What [DuoTutorialWorkspaceWriter] left behind, read by the handler in the job's `done` callback
 * (design §8.2). The job's `IStatus` is for the platform; **whether an editor opens is decided from
 * this alone.**
 */
sealed interface WriterOutcome {
  /** The owned Tutorial file, to be opened on the UI thread after a re-verification under the root rule. */
  data class Ready(val file: IFile) : WriterOutcome

  /** The in-job re-read of the workspace hit a refusal row; nothing was changed. */
  data class Refused(val reason: RefuseReason) : WriterOutcome

  /** An exception; whatever this run had made before the property write is compensated. */
  data object Failed : WriterOutcome

  /**
   * Cancelled — including the case where the job never ran because it was cancelled while queued
   * behind another root-rule job, which is why the writer starts out in this state (§9.2).
   */
  data object Cancelled : WriterOutcome
}
