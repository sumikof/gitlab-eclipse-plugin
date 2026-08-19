package com.gitlab.eclipse.clone.handlers

import com.gitlab.eclipse.clone.CloneDestinationInspector
import com.gitlab.eclipse.clone.CloneDestinationPrompt
import com.gitlab.eclipse.clone.CloneMessages
import com.gitlab.eclipse.clone.CloneOutcome
import com.gitlab.eclipse.clone.CloneTargetLookup
import com.gitlab.eclipse.clone.ClonedProjectImporter
import com.gitlab.eclipse.clone.ImportSkipReason
import com.gitlab.eclipse.clone.RepositoryCloner
import com.gitlab.eclipse.clone.RepositorySource
import com.gitlab.eclipse.clone.WikiUrlDeriver
import com.gitlab.eclipse.navigation.GitLabProjectUrlResolver
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.resources.IFile
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
 * The clone job's name, and the only place the "do not write into the destination" warning can
 * survive. It must NOT go through the monitor: `EclipseProgressMonitorAdapter.start` performs
 * the single `beginTask` the Eclipse monitor contract allows (so this handler may never call
 * `beginTask` itself), and JGit rewrites `subTask` continuously with its own phase names, so a
 * warning placed there is erased within moments. The job name is rendered by the Progress view
 * for the job's entire lifetime and nothing JGit does can overwrite it.
 */
private const val JOB_NAME = "GitLab Wiki clone(clone 中は指定した場所に書き込まないでください)"

/** Entry gate, before the clone flow exists; same shape as the snippets handlers' gate text. */
private const val NO_EDITOR_MESSAGE =
  "GitLab: Open a file in the repository whose wiki you want to clone."

/**
 * `gl.cloneWiki` (design F5): clones the wiki of the GitLab project that owns the active
 * editor's file and imports it into the workspace as a project.
 *
 * The wiki URL is always derived from the API's `http_url_to_repo` ([WikiUrlDeriver]), never
 * from the repository's git remote — the clone is HTTPS-fixed, so a repository with only an SSH
 * remote must not abort the flow (C15). The exact same URL string is handed to both
 * [CloneDestinationInspector.inspect] and [RepositoryCloner.clone]: the (a0) check compares
 * `origin` by string equality, and only an identically-spelled URL round-trips through JGit
 * byte-identically.
 *
 * Threading:
 * - [execute] runs on the UI thread. It checks the active editor, schedules [cloneJob] and
 *   returns immediately — it never blocks the UI thread on the clone.
 * - Everything else — project resolution, the REST lookup, the destination prompt dispatch, the
 *   inspection, the clone and the import — runs in the job. The whole flow ends in UI
 *   notifications, so it must not run on the shared `CoroutineScope` (a failure escaping there
 *   silently kills every later `launch` on that plain-`Job` scope, issue #16); a platform [Job]
 *   also carries the [IProgressMonitor] the clone's cancellation depends on.
 * - Dialogs the background flow blocks on — the destination prompt, the (a0) adoption consent,
 *   and the importer's orphan-registration consent — hop to the UI thread with `syncExec` and
 *   return the answer. This cannot deadlock: [execute] has already returned, so the UI thread
 *   is free to run the dialog while the job thread waits.
 * - Fire-and-forget notifications hop with `asyncExec` ([uiNotify]), the pattern of
 *   `CreateSnippetPatchHandler`.
 *
 * Cancelling either input dialog ends the flow with zero side effects: no clone, no directory,
 * and the destination is never pre-created — JGit creates it when the clone starts.
 */
class CloneWikiHandler : AbstractHandler() {
  private val logger by lazy { logger<CloneWikiHandler>() }
  private val platformUtils = PlatformUtils()

  /** [UI] Step 1: the active editor's file; without one, notify and stop before any job exists. */
  override fun execute(event: ExecutionEvent) {
    val file = platformUtils.getActiveTextEditor()
      ?.editorInput?.getAdapter(IFile::class.java)?.location?.toFile()
    if (file == null) {
      notifyHere(NO_EDITOR_MESSAGE)
      return
    }
    cloneJob(file).schedule()
  }

  private fun cloneJob(file: File): Job {
    val job = object : Job(JOB_NAME) {
      override fun run(monitor: IProgressMonitor): IStatus = runFlow(file, monitor)
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
  private fun runFlow(file: File, monitor: IProgressMonitor): IStatus =
    try {
      cloneWiki(file, monitor)
    } catch (_: OperationCanceledException) {
      Status.CANCEL_STATUS
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      logger.error("Wiki clone flow failed: ${e.javaClass.name}")
      uiNotify(CloneMessages.cloneFailed)
      Status.OK_STATUS
    }

  /** [background] Steps 2-5 of the design's F5 flow; hands off to [cloneInto] for steps 6-8. */
  private fun cloneWiki(file: File, monitor: IProgressMonitor): IStatus {
    // Step 2: resolve which GitLab project owns the file. The resolver's Warn carries its own
    // display text (e.g. "not in the project repository", "no GitLab remote").
    val project = when (val context = GitLabProjectUrlResolver().resolveContextForFile(file)) {
      is GitLabProjectUrlResolver.ContextResolution.Warn -> {
        uiNotify(context.message)
        return Status.OK_STATUS
      }
      is GitLabProjectUrlResolver.ContextResolution.Ok -> context.project
    }
    // Step 3: the wiki URL comes from the API's http_url_to_repo, never from the git remote —
    // an SSH-only remote must not abort the flow. NotFound / NoCloneUrl / NotConnected / Failed
    // all stop here with the design's canned error text; the clone is never started.
    val lookup = CloneTargetLookup().lookup(project.namespaceWithPath) { candidate ->
      candidate.trimEnd('/') == project.instanceUrl.trimEnd('/')
    }
    if (lookup !is CloneTargetLookup.Result.Ok) {
      uiNotify(CloneMessages.cloneFailed)
      return Status.OK_STATUS
    }
    val wikiUrl = WikiUrlDeriver.derive(lookup.httpUrlToRepo)
    // Step 4 [UI hop]: where to clone. Cancel = zero side effects: no clone, nothing created.
    val destination = promptDestination(suggestedFolderName(wikiUrl)) ?: return Status.CANCEL_STATUS
    if (monitor.isCanceled) return Status.CANCEL_STATUS
    // Step 5: entry inspection, with the SAME url string the clone will use.
    return when (CloneDestinationInspector().inspect(destination, wikiUrl)) {
      CloneDestinationInspector.Verdict.Occupied -> {
        uiNotify(CloneMessages.occupied)
        Status.OK_STATUS
      }
      CloneDestinationInspector.Verdict.SameRepository -> {
        // (a0) adoption consent — deliberately distinct from the importer's orphan consent.
        if (askUser(CloneMessages.adoptConsent)) {
          importAndNotify(destination, RepositorySource.ADOPTED_EXISTING)
        } else {
          uiNotify(CloneMessages.occupied)
        }
        Status.OK_STATUS
      }
      CloneDestinationInspector.Verdict.Empty -> cloneInto(wikiUrl, lookup.instanceUrl, destination, monitor)
    }
  }

  /**
   * [background] Steps 6-8: the clone itself, on this job's monitor (which
   * `EclipseProgressMonitorAdapter` opens with the one permitted `beginTask`), then the import.
   * After a cancel or failure the two (b) texts are chosen by [CloneDestinationInspector
   * .hasLeftovers] — "is it non-empty?", not "does it exist?": a directory the user created
   * beforehand survives JGit's cleanup, so `exists()` would claim leftovers that are gone.
   */
  private fun cloneInto(wikiUrl: String, instanceUrl: String, destination: File, monitor: IProgressMonitor): IStatus {
    val outcome = RepositoryCloner().clone(wikiUrl, destination, instanceUrl, monitor)
    when (outcome) {
      RepositoryCloner.Outcome.Busy -> uiNotify(CloneMessages.busy)
      RepositoryCloner.Outcome.Cancelled, is RepositoryCloner.Outcome.Failed ->
        uiNotify(incompleteMessage(destination))
      RepositoryCloner.Outcome.Succeeded -> importAndNotify(destination, RepositorySource.CLONED_NOW)
    }
    return if (outcome == RepositoryCloner.Outcome.Cancelled) Status.CANCEL_STATUS else Status.OK_STATUS
  }

  /**
   * [background] Step 8: the import runs on this job thread (the importer never touches SWT);
   * its orphan-registration consent hops to the UI thread through [askUser]. The extra catch is
   * the containment the importer defers to its caller — e.g. `getProject` throwing on a
   * pathological `.project` name — mapped to the import's own failure wording, type name only.
   */
  private fun importAndNotify(destination: File, source: RepositorySource) {
    val outcome = try {
      ClonedProjectImporter(confirm = ::askUser).import(destination, source)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      logger.error("Importing the clone failed: ${e.javaClass.name}")
      CloneOutcome.ImportSkipped(destination, ImportSkipReason.IMPORT_FAILED, source, destination.name)
    }
    uiNotify(importNotification(outcome, source, destination))
  }

  /**
   * [UI hop, blocking] Runs the two-step destination prompt on the UI thread and waits for the
   * answer. A missing workbench shell is treated as cancel: zero side effects.
   */
  private fun promptDestination(suggestedFolderName: String): File? {
    var destination: File? = null
    currentDisplay.syncExec {
      val shell = PlatformUI.getWorkbench().activeWorkbenchWindow?.shell
      if (shell == null) {
        logger.warn("Wiki clone: no workbench shell to prompt on; treating as cancel.")
      } else {
        destination = CloneDestinationPrompt().prompt(shell, suggestedFolderName)
      }
    }
    return destination
  }

  /**
   * [UI hop, blocking] Yes/no question from the job thread: `syncExec`, not `asyncExec`,
   * because the flow blocks on the answer — safe from deadlock since [execute] has returned
   * and the UI thread is free. Also passed to [ClonedProjectImporter] as its consent callback.
   */
  private fun askUser(question: String): Boolean {
    var answer = false
    currentDisplay.syncExec {
      val shell = PlatformUI.getWorkbench().activeWorkbenchWindow?.shell
      answer = MessageDialog.openQuestion(shell, "GitLab", question)
    }
    return answer
  }

  /** [UI thread] Notification shown from [execute] itself, before any job exists. */
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
    }.onFailure { logger.error("Could not show a wiki clone notification: ${it.javaClass.name}") }
  }
}

/**
 * (b) after a cancelled or failed clone: the two texts are chosen by "is the destination
 * non-empty?", never by "does it exist?" — a directory the user created beforehand survives
 * JGit's cleanup, so `exists()` would claim leftovers that were already removed (C24/C32).
 */
private fun incompleteMessage(destination: File): String =
  if (CloneDestinationInspector().hasLeftovers(destination)) {
    CloneMessages.cloneIncompleteLeftovers(destination)
  } else {
    CloneMessages.cloneIncompleteNothingLeft
  }

/** Maps the import's outcome to its notification; every string comes from [CloneMessages]. */
private fun importNotification(outcome: CloneOutcome, source: RepositorySource, destination: File): String =
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

/** JGit's "humanish" default: the wiki URL's last segment without `.git`, e.g. `project.wiki`. */
private fun suggestedFolderName(wikiUrl: String): String =
  wikiUrl.trimEnd('/').substringAfterLast('/').removeSuffix(".git")
