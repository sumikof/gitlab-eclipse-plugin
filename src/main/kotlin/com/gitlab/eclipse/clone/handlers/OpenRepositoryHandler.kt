package com.gitlab.eclipse.clone.handlers

import com.gitlab.eclipse.clone.CloneDestinationPrompt
import com.gitlab.eclipse.clone.CloneFlow
import com.gitlab.eclipse.clone.CloneMessages
import com.gitlab.eclipse.clone.CloneTargetLookup
import com.gitlab.eclipse.clone.ProjectPathPrompt
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.OperationCanceledException
import org.eclipse.core.runtime.Status
import org.eclipse.core.runtime.jobs.Job
import org.eclipse.jface.dialogs.MessageDialog
import org.eclipse.ui.PlatformUI
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * The clone job's label; [CloneMessages.cloneJobName] appends the "do not write into the
 * destination" warning, and the job name is the only place that warning can survive. It must
 * NOT go through the monitor: `EclipseProgressMonitorAdapter.start` performs the single
 * `beginTask` the Eclipse monitor contract allows (so this handler may never call `beginTask`
 * itself), and JGit rewrites `subTask` continuously with its own phase names, so a warning
 * placed there is erased within moments. The job name is rendered by the Progress view for the
 * job's entire lifetime and nothing JGit does can overwrite it.
 */
private const val JOB_LABEL = "GitLab repository clone"

/** Title shared by the path prompt and the two destination dialogs, so the flow reads as one. */
private const val DIALOG_TITLE = "Open GitLab Repository"

/**
 * `gl.openRepository` (design F7): asks for a GitLab project path, resolves its HTTPS clone url
 * through the API and clones it into the workspace as a project.
 *
 * The only difference from `CloneWikiHandler` is this front half. F5 starts from the active
 * editor's file and derives the wiki url; F7 starts from a typed path and uses the API's
 * `http_url_to_repo` verbatim — no wiki derivation, no re-spelling — because [CloneFlow] compares
 * the destination's `origin` against that exact string. The lookup accepts any instance (F5
 * pins the file's own instance; a typed path has no instance to pin to), and the `instanceUrl`
 * handed to [CloneFlow] is the one the lookup returned, never a re-read of configuration.
 *
 * Steps 4-8 — destination prompt, entry inspection, clone, import, notification — are entirely
 * [CloneFlow] and are not reimplemented here. The four SWT helpers below are duplicated from
 * `CloneWikiHandler` on purpose: they are the untestable SWT boundary, and a shared base class
 * would couple two commands that have no other relationship.
 *
 * Threading:
 * - [execute] runs on the UI thread and may therefore open the path dialog directly, with no
 *   hop. It then schedules [cloneJob] and returns; it never blocks the UI thread on the clone.
 * - Everything else — the REST lookup, the destination prompt dispatch, the inspection, the
 *   clone and the import — runs in the job. The flow ends in UI notifications, so it must not
 *   run on the shared `CoroutineScope` (a failure escaping there silently kills every later
 *   `launch` on that plain-`Job` scope, issue #16); a platform [Job] also carries the
 *   [IProgressMonitor] the clone's cancellation depends on.
 * - Dialogs the background flow blocks on hop to the UI thread with `syncExec` and return the
 *   answer. This cannot deadlock: [execute] has already returned, so the UI thread is free to
 *   run the dialog while the job thread waits. Fire-and-forget notifications hop with
 *   `asyncExec` ([uiNotify]).
 *
 * Cancelling any input dialog ends the flow with zero side effects: cancelling the path prompt
 * does not even create the job, and cancelling the destination prompt means no clone, no
 * directory — the destination is never pre-created, JGit creates it when the clone starts.
 */
class OpenRepositoryHandler : AbstractHandler() {
  private val logger by lazy { logger<OpenRepositoryHandler>() }

  /**
   * [UI] Step 1: which project? Asked here rather than in the job because a cancelled prompt
   * must leave nothing behind — not even a job entry in the Progress view.
   */
  override fun execute(event: ExecutionEvent) {
    val shell = PlatformUI.getWorkbench().activeWorkbenchWindow?.shell
    if (shell == null) {
      logger.warn("Open repository: no workbench shell to prompt on; nothing to do.")
      return
    }
    val projectPath = ProjectPathPrompt().prompt(shell, DIALOG_TITLE) ?: return
    cloneJob(projectPath).schedule()
  }

  private fun cloneJob(projectPath: String): Job {
    val job = object : Job(CloneMessages.cloneJobName(JOB_LABEL)) {
      override fun run(monitor: IProgressMonitor): IStatus = runFlow(projectPath, monitor)
    }
    job.isUser = true
    return job
  }

  /**
   * [background] The containment boundary: nothing may escape the job body. A failure status
   * would raise a platform error dialog over internals, and silence would strand the user, so
   * unexpected failures log the exception's type name only (messages can carry URLs, A9) and
   * show the design's canned error text.
   */
  private fun runFlow(projectPath: String, monitor: IProgressMonitor): IStatus =
    try {
      openRepository(projectPath, monitor)
    } catch (_: OperationCanceledException) {
      Status.CANCEL_STATUS
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      logger.error("Repository clone flow failed: ${e.javaClass.name}")
      uiNotify(CloneMessages.cloneFailed)
      Status.OK_STATUS
    }

  /**
   * [background] Step 2 of the design's F7 flow; hands steps 4-8 to [CloneFlow].
   *
   * The lookup accepts any configured instance — unlike F5 there is no file, hence no instance,
   * to pin the typed path to. An unknown path gets its own wording and stops the flow before
   * anything is cloned; the three remaining failures share the canned clone-failed text, since
   * telling the user which internal step failed would not change what they can do about it.
   */
  private fun openRepository(projectPath: String, monitor: IProgressMonitor): IStatus {
    val target = when (val lookup = CloneTargetLookup().lookup(projectPath) { true }) {
      is CloneTargetLookup.Result.Ok -> lookup
      CloneTargetLookup.Result.NotFound -> {
        uiNotify(CloneMessages.projectNotFound)
        return Status.OK_STATUS
      }
      CloneTargetLookup.Result.NoCloneUrl -> {
        uiNotify(CloneMessages.cloneFailed)
        return Status.OK_STATUS
      }
      CloneTargetLookup.Result.NotConnected -> {
        uiNotify(CloneMessages.cloneFailed)
        return Status.OK_STATUS
      }
      is CloneTargetLookup.Result.Failed -> {
        // `type` is already a type name only, so it is safe to log (A9).
        logger.error("Repository lookup failed: ${lookup.type}")
        uiNotify(CloneMessages.cloneFailed)
        return Status.OK_STATUS
      }
    }
    // Steps 4-8: the shared flow, with this handler's SWT hops injected. The API's url goes in
    // as-is — the inspection and the clone must see the very same string.
    val flow = CloneFlow(
      prompt = { promptDestination(DIALOG_TITLE, it) },
      confirm = ::askUser,
      notify = ::uiNotify,
    )
    return when (flow.run(target.httpUrlToRepo, target.instanceUrl, monitor)) {
      CloneFlow.Result.CANCELLED -> Status.CANCEL_STATUS
      CloneFlow.Result.COMPLETED -> Status.OK_STATUS
    }
  }

  /**
   * [UI hop, blocking] Runs the two-step destination prompt on the UI thread and waits for the
   * answer. A missing workbench shell is treated as cancel: zero side effects.
   */
  private fun promptDestination(title: String, suggestedFolderName: String): File? {
    var destination: File? = null
    currentDisplay.syncExec {
      val shell = PlatformUI.getWorkbench().activeWorkbenchWindow?.shell
      if (shell == null) {
        logger.warn("Repository clone: no workbench shell to prompt on; treating as cancel.")
      } else {
        destination = CloneDestinationPrompt().prompt(shell, title, suggestedFolderName)
      }
    }
    return destination
  }

  /**
   * [UI hop, blocking] Yes/no question from the job thread: `syncExec`, not `asyncExec`,
   * because the flow blocks on the answer — safe from deadlock since [execute] has returned
   * and the UI thread is free. Also passed to [CloneFlow] (and through it to the importer) as
   * the consent callback.
   */
  private fun askUser(question: String): Boolean {
    var answer = false
    currentDisplay.syncExec {
      val shell = PlatformUI.getWorkbench().activeWorkbenchWindow?.shell
      answer = MessageDialog.openQuestion(shell, "GitLab", question)
    }
    return answer
  }

  /** [UI thread] Shows the dialog: the body of [uiNotify], and callable directly on the UI thread. */
  private fun notifyHere(message: String) {
    val shell = PlatformUI.getWorkbench().activeWorkbenchWindow?.shell
    MessageDialog.openInformation(shell, "GitLab", message)
  }

  /**
   * [background → UI, fire-and-forget] Guarded so a torn-down workbench cannot throw out of the
   * job's catch blocks; the failure is logged as a type name and the flow ends quietly.
   */
  private fun uiNotify(message: String) {
    runCatching {
      currentDisplay.asyncExec { notifyHere(message) }
    }.onFailure { logger.error("Could not show a repository clone notification: ${it.javaClass.name}") }
  }
}
