package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.JobService
import com.gitlab.eclipse.api.PipelineService
import com.gitlab.eclipse.mergerequests.CurrentBranch
import com.gitlab.eclipse.mergerequests.CurrentBranchGitReader
import com.gitlab.eclipse.mergerequests.CurrentBranchInfo
import com.gitlab.eclipse.mergerequests.CurrentBranchMrLookup
import com.gitlab.eclipse.mergerequests.EffectiveRef
import com.gitlab.eclipse.mergerequests.RepositoryContext
import com.gitlab.eclipse.mergerequests.RepositoryContextResolver
import com.gitlab.eclipse.views.sidebar.autorefresh.HeadKey
import com.gitlab.eclipse.views.sidebar.autorefresh.TrackedHead
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches the sidebar's "For current branch" section (design CI-04 §2.3): the one path both the
 * full refresh and the auto refresh's soft refresh go through. SWT-free: every slot change goes
 * out through the caller's `apply`, which the view runs on the UI thread against the live slot
 * (and drops when its refresh is no longer current).
 */
class CurrentBranchSectionFetcher(
  private val resolver: RepositoryContextResolver,
  private val reader: CurrentBranchGitReader,
  private val mrLookup: CurrentBranchMrLookup,
  private val pipelineService: PipelineService,
  private val jobService: JobService,
  private val apiClient: GitLabApiClient,
  private val errorLog: (String, Throwable?) -> Unit,
  private val warnLog: (String) -> Unit,
) {
  // Stages (mr / pipeline / jobs) whose latest outcome was a failure. The soft path logs a failure
  // only when it starts such a streak, so an unreachable server is reported once, not every 30 s.
  private val failingStages: MutableSet<String> = ConcurrentHashMap.newKeySet()

  /**
   * Shared pre-step, ONCE per refresh: resolve the repository, then read the branch snapshot and
   * its effective ref, so the MR lookup and the pipeline ref work from the SAME branch state — a
   * checkout switch mid-refresh cannot make them diverge (R2-1). No repository settles the slot as
   * [NoRepository] without starting sub-tasks. Otherwise the MR and pipeline fetches run as
   * independent sub-tasks, each settling its own side of the [Resolved] payload; the other side
   * is preserved because each `update` re-reads the slot it is applied to, so the two sub-tasks
   * never race each other's halves.
   *
   * Every slot change goes out as `apply(gitDir, update)`: the caller runs `update` on the slot's
   * current value and records `gitDir` (null for [NoRepository]) as the repository that value now
   * describes — in the same UI-thread step, so the two never disagree.
   *
   * [soft] (the CI-04 auto refresh) differs from the full path in three ways only: with a
   * [previous] [Resolved] displayed for this same repository ([previousGitDir], as recorded by
   * `apply`) it paints no placeholder; it applies nothing for
   * a side whose new success equals the shown one; and failures are logged by [report]'s soft rule.
   *
   * Returns the repository and HEAD this fetch looked at, or null when no repository resolved.
   */
  suspend fun fetch(
    activeEditorFile: File?,
    previous: CurrentBranchSectionInput?,
    previousGitDir: String?,
    soft: Boolean,
    apply: SectionApply,
  ): TrackedHead? {
    // JGit enumeration/resolution (IO). resolveNonInteractive and read never throw by
    // contract; a null context means no repository resolved non-interactively (none, or
    // several and no active editor to disambiguate) — distinct from a resolved repository
    // with no MR, which renders as "No merge request found".
    val context = resolver.resolveNonInteractive(activeEditorFile)
    if (context == null) {
      apply(null) { NoRepository }
      return null
    }
    val branch = reader.read(File(context.gitDir))
    val effectiveRef = EffectiveRef.resolve(branch, context.remoteName)
    // Soft refresh over a shown result: keep showing it until the new values arrive (no Loading
    // flicker), and keep each side whose new value equals the shown one (see [unchanged]).
    val shown = if (soft && previousGitDir == context.gitDir) previous as? Resolved else null
    // Repository resolved, both sides still loading: paint the two placeholders now.
    if (shown == null) apply(context.gitDir) { Resolved(mr = null, pipeline = null) }

    supervisorScope {
      launch {
        val mrResult = mrResultFor(context, branch)
        report(STAGE_MR, soft, mrResult.exceptionOrNull()) { "Failed to load current-branch merge request." }
        if (!unchanged(shown?.mr, mrResult)) apply(context.gitDir) { resolvedOf(it).copy(mr = mrResult) }
      }
      launch {
        val pipelineResult = pipelineResultFor(context, effectiveRef, soft)
        report(STAGE_PIPELINE, soft, pipelineResult.exceptionOrNull()) { "Failed to load current-branch pipeline." }
        if (!unchanged(shown?.pipeline, pipelineResult)) {
          apply(context.gitDir) { resolvedOf(it).copy(pipeline = pipelineResult) }
        }
      }
    }
    return TrackedHead(context.gitDir, headKeyOf(branch))
  }

  private fun mrResultFor(context: RepositoryContext, branch: CurrentBranch): Result<CurrentBranchInfo> =
    try {
      Result.success(mrLookup.lookup(context, branch))
    } catch (e: CancellationException) {
      throw e // Like the pipeline side: a cancelled refresh paints and logs nothing.
    } catch (e: Throwable) {
      Result.failure(e) // Throwable, as the runCatching this replaces: any other error renders as before.
    }

  private suspend fun pipelineResultFor(context: RepositoryContext, effectiveRef: String?, soft: Boolean): Result<PipelineSnapshot?> =
    // A detached HEAD has no ref to query pipelines for: a settled "no pipeline"
    // (success(null) → no row at all), not an error.
    if (effectiveRef == null) {
      Result.success(null)
    } else {
      try {
        Result.success(fetchSnapshot(context, effectiveRef, soft))
      } catch (e: CancellationException) {
        throw e // Propagate: a cancelled refresh paints nothing (not a failure row).
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

  // Fresh-payload invariant (RefreshSlots KDoc): each sub-task's payload is freshly constructed
  // by its own fetch — never a cached/reused instance — so the coordinator's identity-keyed memo
  // sees every applied settle as a change (the soft path's reuse happens by NOT applying, see
  // [unchanged]). The blocking fetches inside have no suspension points, so
  // cancellation (a newer refresh() or dispose() cancelling the fetch job) is observed
  // explicitly: ensureActive between the two GETs, isActive between jobs pages. The resulting
  // CancellationException must propagate (never be runCatching'd into a rendered failure) — it
  // cancels this child cleanly and paints nothing.
  private suspend fun fetchSnapshot(context: RepositoryContext, ref: String, soft: Boolean): PipelineSnapshot? {
    // Captured ONCE per snapshot fetch (design doc §8.5): both GETs below are pinned to
    // this connection, and its non-secret tags ride the snapshot onto the nodes, so a
    // concurrent gitlab.url/token change can neither split the two GETs across instances
    // nor leave the nodes tagged with a connection they were not fetched over. May throw
    // UnstableConnectionException, which settles as a pipeline-load failure in the
    // enclosing launch's catch — acceptable, no special handling.
    val connection = apiClient.captureConnection()
    val pipeline = pipelineService.getLatestPipelineForRef(context.projectId, ref, connection) ?: return null
    // Cancelled during the synchronous pipeline GET: stop here instead of starting the
    // jobs fetch (which would otherwise run to its 15s paging deadline for nothing).
    currentCoroutineContext().ensureActive()
    val job = currentCoroutineContext()[Job]
    val jobsResult = try {
      Result.success(
        jobService.getJobsForPipeline(
          context.projectId,
          pipeline.id,
          isActive = { job?.isActive != false },
          connection = connection,
        ),
      )
    } catch (e: CancellationException) {
      throw e // Cancelled between jobs pages: cancel the coroutine, don't render a failure.
    } catch (e: Exception) {
      Result.failure(e)
    }
    // Logged so the "Failed to load jobs" child has a matching Error Log entry; the
    // pipeline row itself still renders from the successfully fetched pipeline.
    report(STAGE_JOBS, soft, jobsResult.exceptionOrNull()) { "Failed to load jobs for pipeline #${pipeline.id}." }
    return PipelineSnapshot(pipeline, jobsResult, connection.instanceUrl, connection.authFingerprint)
  }

  /**
   * Logs one stage's outcome. A full refresh logs every failure to the Error Log exactly as before
   * ([fullMessage] with the throwable). A soft refresh logs a WARN with the stage and the exception
   * class only (design §5: no path, branch, URL or message), and only for the first failure of a
   * streak. Any success ends the stage's streak; any failure, on either path, extends it.
   */
  private fun report(stage: String, soft: Boolean, error: Throwable?, fullMessage: () -> String) {
    if (error == null) {
      failingStages.remove(stage)
      return
    }
    val startsStreak = failingStages.add(stage)
    if (!soft) {
      errorLog(fullMessage(), error)
    } else if (startsStreak) {
      warnLog("Current-branch $stage refresh failed: ${error.javaClass.name}")
    }
  }

  private companion object {
    const val STAGE_MR = "mr"
    const val STAGE_PIPELINE = "pipeline"
    const val STAGE_JOBS = "jobs"
  }
}

/**
 * How [CurrentBranchSectionFetcher.fetch] hands out a slot change: the caller applies `update` to
 * the slot's current value and records `gitDir` as the repository that value describes.
 */
typealias SectionApply = (gitDir: String?, update: SectionUpdate) -> Unit

/** One slot change: the new value computed from the slot's current one. */
typealias SectionUpdate = (current: CurrentBranchSectionInput?) -> CurrentBranchSectionInput

/**
 * Whether a soft refresh's [fresh] result for one side equals the [shown] one, so the shown
 * instance stays in the slot: the coordinator memoizes by identity, and an unchanged instance is
 * what keeps that side's nodes — and their expansion — from being rebuilt. Successes only, compared
 * structurally (`==` on the data classes); a failure is always re-applied.
 */
private fun <T> unchanged(shown: Result<T>?, fresh: Result<T>): Boolean =
  shown != null && shown.isSuccess && fresh.isSuccess && shown.getOrNull() == fresh.getOrNull()

/** The side-preserving read of the live slot: the [Resolved] it holds, or two placeholders. */
private fun resolvedOf(current: CurrentBranchSectionInput?): Resolved =
  current as? Resolved ?: Resolved(mr = null, pipeline = null)

/** What the auto refresh compares HEADs by: the branch name, else the detached SHA, else nothing readable. */
internal fun headKeyOf(branch: CurrentBranch): HeadKey =
  when {
    branch.name != null -> HeadKey.Branch(branch.name)
    branch.headSha != null -> HeadKey.Detached(branch.headSha)
    else -> HeadKey.Unavailable
  }
