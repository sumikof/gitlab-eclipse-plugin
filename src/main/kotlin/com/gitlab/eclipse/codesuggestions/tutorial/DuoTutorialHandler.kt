package com.gitlab.eclipse.codesuggestions.tutorial

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.preferences.openGitLabPreferences
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.runtime.jobs.IJobChangeEvent
import org.eclipse.core.runtime.jobs.JobChangeAdapter
import org.eclipse.jface.dialogs.MessageDialog
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.handlers.HandlerUtil
import org.eclipse.ui.preferences.ScopedPreferenceStore

/**
 * Status menu "GitLab Duo Tutorial" (design §8.2, §9.2 F2).
 *
 * On the UI thread this does exactly two things: it checks the `DUO_ENABLED_WITHOUT_GITLAB_PROJECT`
 * preference — the Tutorial is not a GitLab project, so with that off the language server would
 * disable Duo in it (round 22 P1); the user is asked whether to open the preferences instead — and
 * it schedules [DuoTutorialWorkspaceWriter]. **No workspace state is read and no ownership is
 * judged here**: both happen inside the job, under the root rule, where a run that overlaps a
 * half-finished creation waits instead of misjudging it.
 *
 * The job's `done` reports its [WriterOutcome]: `Ready` goes to [DuoTutorialEditorOpener], the
 * refusals and `Failed` become a notification, `Cancelled` nothing.
 */
@Suppress("unused")
class DuoTutorialHandler(
  private val preferences: () -> ScopedPreferenceStore = { service() },
  private val activeWindow: (ExecutionEvent) -> IWorkbenchWindow? = { HandlerUtil.getActiveWorkbenchWindow(it) },
  private val askToOpenPreferences: (IWorkbenchWindow?) -> Boolean = { window ->
    MessageDialog.openQuestion(
      window?.shell,
      DuoTutorialMessages.DIALOG_TITLE,
      DuoTutorialMessages.PREFERENCES_QUESTION,
    )
  },
  private val openPreferences: () -> Unit = { openGitLabPreferences() },
  private val newWriter: () -> DuoTutorialWorkspaceWriter = { DuoTutorialWorkspaceWriter() },
  private val schedule: (DuoTutorialWorkspaceWriter, (WriterOutcome) -> Unit) -> Unit = ::scheduleAndReport,
  private val opener: DuoTutorialEditorOpener = DuoTutorialEditorOpener(),
  private val notify: (String) -> Unit = { NotificationUtils.show(it) },
) : AbstractHandler() {
  private val logger by lazy { logger<DuoTutorialHandler>() }

  override fun execute(event: ExecutionEvent): Any? {
    val window = activeWindow(event)
    if (!preferences().getBoolean(PreferenceConstants.DUO_ENABLED_WITHOUT_GITLAB_PROJECT)) {
      logger.info("GitLab Duo Tutorial: Duo is disabled for non-GitLab projects; asking to open the preferences")
      if (askToOpenPreferences(window)) openPreferences()
      return null
    }
    logger.info("GitLab Duo Tutorial: scheduling the workspace job")
    schedule(newWriter()) { outcome -> report(window, outcome) }
    return null
  }

  /** The job's thread. Everything here either hops to the UI itself or is total. */
  private fun report(window: IWorkbenchWindow?, outcome: WriterOutcome) {
    when (outcome) {
      is WriterOutcome.Ready -> opener.open(window, outcome.file)
      is WriterOutcome.Refused -> notify(DuoTutorialMessages.refusal(outcome.reason))
      WriterOutcome.Failed -> notify(DuoTutorialMessages.CREATE_FAILED)
      WriterOutcome.Cancelled -> Unit
    }
  }
}

/**
 * Production scheduling: report [writer]'s [DuoTutorialWorkspaceWriter.outcome] when the job is
 * done — which the platform also signals for a job cancelled while it was still queued, without
 * ever running it; the outcome's initial value covers that.
 */
internal fun scheduleAndReport(writer: DuoTutorialWorkspaceWriter, onDone: (WriterOutcome) -> Unit) {
  writer.addJobChangeListener(
    object : JobChangeAdapter() {
      override fun done(event: IJobChangeEvent) = onDone(writer.outcome)
    },
  )
  writer.schedule()
}
