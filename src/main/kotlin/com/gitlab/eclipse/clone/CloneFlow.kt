package com.gitlab.eclipse.clone

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.mergerequests.GitOperationGuard
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.runtime.IProgressMonitor
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Steps 4-8 of the design's clone flow (destination prompt → entry inspection → clone → import →
 * notification), shared by F5 (`gl.cloneWiki`) and F7 (open repository). The three UI touches are
 * injected as functions; this class never references SWT, so the orchestration can be exercised
 * headless.
 *
 * The exact same URL string is handed to both [CloneDestinationInspector.inspect] and
 * [RepositoryCloner.clone]: the (a0) check compares `origin` by string equality, and only an
 * identically-spelled URL round-trips through JGit byte-identically. Likewise the `instanceUrl`
 * given to [run] must be the one the caller's lookup returned — never a re-read of configuration.
 *
 * Threading: [run] is called on a background job thread and blocks on the injected [prompt] and
 * [confirm] hops (the caller implements them with `syncExec`); [notify] is fire-and-forget. The
 * clone runs on the job's [IProgressMonitor], which `EclipseProgressMonitorAdapter` opens with
 * the one `beginTask` the Eclipse monitor contract allows — so neither this class nor its caller
 * may ever call `beginTask` themselves.
 *
 * Cancelling the destination prompt ends the flow with zero side effects: no clone, no directory,
 * and the destination is never pre-created — JGit creates it when the clone starts.
 *
 * The clone and the (a0) adoption take the same [GitOperationGuard] key — the clone inside
 * [RepositoryCloner], the adoption here — and they are exclusive branches, so the non-reentrant
 * guard is never acquired twice in one run. The spans differ: the cloner releases the key before
 * the clone's import runs, while the adoption holds it across its own import, which is what keeps
 * a still-running clone from having its half-written tree adopted.
 */
class CloneFlow(
  /** [UI hop, blocking] Argument is the suggested folder name. Cancel is null. */
  private val prompt: (String) -> File?,
  /** [UI hop, blocking] Yes/no question; also handed to the importer as its consent callback. */
  private val confirm: (String) -> Boolean,
  /** [UI hop, fire-and-forget] One notification. */
  private val notify: (String) -> Unit,
  private val inspector: CloneDestinationInspector = CloneDestinationInspector(),
  private val cloner: RepositoryCloner = RepositoryCloner(),
  private val importProject: (File, RepositorySource) -> CloneOutcome = { destination, source ->
    ClonedProjectImporter(confirm).import(destination, source)
  },
  /**
   * The SAME instance [RepositoryCloner] takes (a Koin `single`), which is the whole point: the
   * adoption path has to be excluded against a clone that is running into the same directory.
   */
  private val guard: GitOperationGuard = service(),
) {
  private val logger by lazy { logger<CloneFlow>() }

  /** Two values the caller maps onto `IStatus`; kept Eclipse-free on purpose. */
  enum class Result { COMPLETED, CANCELLED }

  /** [background] Steps 4-5; hands off to [cloneInto] for steps 6-8. */
  fun run(cloneUrl: String, instanceUrl: String, monitor: IProgressMonitor): Result {
    // The caller's lookup ran before this and can take a while; a cancel there must not still open
    // the destination dialog and then discard the answer.
    if (monitor.isCanceled) return Result.CANCELLED
    // Step 4 [UI hop]: where to clone. Cancel = zero side effects: no clone, nothing created.
    val destination = prompt(suggestedFolderName(cloneUrl)) ?: return Result.CANCELLED
    if (monitor.isCanceled) return Result.CANCELLED
    // Step 5: entry inspection, with the SAME url string the clone will use.
    return when (inspector.inspect(destination, cloneUrl)) {
      CloneDestinationInspector.Verdict.Occupied -> {
        notify(CloneMessages.occupied)
        Result.COMPLETED
      }
      CloneDestinationInspector.Verdict.SameRepository -> {
        adopt(destination)
        Result.COMPLETED
      }
      CloneDestinationInspector.Verdict.Empty -> cloneInto(cloneUrl, instanceUrl, destination, monitor)
    }
  }

  /**
   * [background] (a0) adoption of a repository that is already there, under the clone's own guard
   * key.
   *
   * A clone still running into this directory has already written `origin` — which is exactly what
   * made the verdict SameRepository — and its failure path lets JGit's cleanup empty the directory
   * again. Importing it in the meantime would register a project over a tree that is about to be
   * emptied, so a held guard means "refuse", and the consent question is never asked: its answer
   * could only have been discarded.
   */
  private fun adopt(destination: File) {
    val done = guard.withRepo(CloneGuardKey.of(destination)) {
      // (a0) adoption consent — deliberately distinct from the importer's orphan consent.
      if (confirm(CloneMessages.adoptConsent)) {
        importAndNotify(destination, RepositorySource.ADOPTED_EXISTING)
      } else {
        notify(CloneMessages.occupied)
      }
    }
    if (done == null) notify(CloneMessages.busy)
  }

  /**
   * [background] Steps 6-8: the clone itself, on this job's monitor (which
   * `EclipseProgressMonitorAdapter` opens with the one permitted `beginTask`), then the import.
   * After a cancel or failure the two (b) texts are chosen by [CloneDestinationInspector
   * .hasLeftovers] — "is it non-empty?", not "does it exist?": a directory the user created
   * beforehand survives JGit's cleanup, so `exists()` would claim leftovers that are gone.
   */
  private fun cloneInto(cloneUrl: String, instanceUrl: String, destination: File, monitor: IProgressMonitor): Result {
    val outcome = cloner.clone(cloneUrl, destination, instanceUrl, monitor)
    when (outcome) {
      RepositoryCloner.Outcome.Busy -> notify(CloneMessages.busy)
      RepositoryCloner.Outcome.Cancelled, is RepositoryCloner.Outcome.Failed ->
        notify(incompleteMessage(destination))
      RepositoryCloner.Outcome.Succeeded -> importAndNotify(destination, RepositorySource.CLONED_NOW)
    }
    return if (outcome == RepositoryCloner.Outcome.Cancelled) Result.CANCELLED else Result.COMPLETED
  }

  /**
   * [background] Step 8: the import runs on the job thread (the importer never touches SWT);
   * its orphan-registration consent hops to the UI thread through the injected [confirm]. The
   * extra catch is the containment the importer defers to its caller — e.g. `getProject`
   * throwing on a pathological `.project` name — mapped to the import's own failure wording,
   * type name only.
   */
  private fun importAndNotify(destination: File, source: RepositorySource) {
    val outcome = try {
      importProject(destination, source)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      logger.error("Importing the clone failed: ${e.javaClass.name}")
      CloneOutcome.ImportSkipped(destination, ImportSkipReason.IMPORT_FAILED, source, destination.name)
    }
    notify(CloneNotifications.importNotification(outcome))
  }

  /**
   * (b) after a cancelled or failed clone: the two texts are chosen by "is the destination
   * non-empty?", never by "does it exist?" — a directory the user created beforehand survives
   * JGit's cleanup, so `exists()` would claim leftovers that were already removed (C24/C32).
   */
  private fun incompleteMessage(destination: File): String =
    if (inspector.hasLeftovers(destination)) {
      CloneMessages.cloneIncompleteLeftovers(destination)
    } else {
      CloneMessages.cloneIncompleteNothingLeft
    }
}

/** JGit's "humanish" default: the clone URL's last segment without `.git`, e.g. `project.wiki`. */
private fun suggestedFolderName(cloneUrl: String): String =
  cloneUrl.trimEnd('/').substringAfterLast('/').removeSuffix(".git")
