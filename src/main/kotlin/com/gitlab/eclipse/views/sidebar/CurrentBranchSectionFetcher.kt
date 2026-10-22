package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.BridgeService
import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.JobService
import com.gitlab.eclipse.api.MergeRequestService
import com.gitlab.eclipse.api.PipelineService
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.ci.PipelineKind
import com.gitlab.eclipse.ci.SelectedPipeline
import com.gitlab.eclipse.ci.selectPipeline
import com.gitlab.eclipse.mergerequests.CurrentBranch
import com.gitlab.eclipse.mergerequests.CurrentBranchGitReader
import com.gitlab.eclipse.mergerequests.CurrentBranchInfo
import com.gitlab.eclipse.mergerequests.CurrentBranchMrLookup
import com.gitlab.eclipse.mergerequests.EffectiveRef
import com.gitlab.eclipse.mergerequests.HeadTagReader
import com.gitlab.eclipse.mergerequests.RepositoryContext
import com.gitlab.eclipse.mergerequests.RepositoryContextResolver
import com.gitlab.eclipse.views.sidebar.autorefresh.HeadKey
import com.gitlab.eclipse.views.sidebar.autorefresh.TrackedHead
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
  private val bridgeService: BridgeService,
  private val mrService: MergeRequestService,
  private val tagReader: HeadTagReader,
  private val apiClient: GitLabApiClient,
  private val errorLog: (String, Throwable?) -> Unit,
  private val warnLog: (String) -> Unit,
) {
  // Stages (mr / mr-pipelines / pipeline / tag / jobs / bridges) whose latest outcome was a failure. The
  // soft path logs a failure only when it starts such a streak, so an unreachable server is reported
  // once, not every 30 s.
  private val failingStages: MutableSet<String> = ConcurrentHashMap.newKeySet()

  /**
   * Shared pre-step, ONCE per refresh: resolve the repository, then read the branch snapshot and
   * its effective ref, so the MR lookup and the pipeline ref work from the SAME branch state — a
   * checkout switch mid-refresh cannot make them diverge (R2-1). No repository settles the slot as
   * [NoRepository] without starting sub-tasks. Otherwise the MR side and the pipeline side run as
   * sub-tasks, each settling its own half of the [Resolved] payload; the other half is preserved
   * because each `update` re-reads the slot it is applied to, so the two sub-tasks never race each
   * other's halves.
   *
   * CI-05 §4.1: the MR search runs ONCE, as a shared [Deferred] both sides await — the MR side to
   * add closes_issues, the pipeline side to list the MR's pipelines and pick between them and the
   * branch pipeline. The deferred never fails: any error is folded into its [Result] (the MR side
   * renders it; the pipeline side treats it as "no MR"), except [CancellationException], which
   * cancels it and therefore both awaiting sides, so a cancelled refresh paints nothing anywhere.
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
    val target = pipelineTargetOf(context, branch)
    // Soft refresh over a shown result: keep showing it until the new values arrive (no Loading
    // flicker), and keep each side whose new value equals the shown one (see [unchanged]).
    val shown = if (soft && previousGitDir == context.gitDir) previous as? Resolved else null
    // Repository resolved, both sides still loading: paint the two placeholders now.
    if (shown == null) apply(context.gitDir) { Resolved(mr = null, pipeline = null) }

    // Captured ONCE per refresh, on this IO thread, and only when a GET will actually use it
    // (a branch, or a detached HEAD with exactly one tag — R1-5). Every GET of both sides is pinned
    // to it, and its non-secret tags ride the snapshot onto the nodes, so a concurrent
    // gitlab.url/token change can neither split the GETs across instances or accounts nor leave the
    // nodes tagged with a connection they were not fetched over. A capture failure keeps today's
    // failure scope: the MR side runs unpinned, the pipeline side settles as that failure.
    val connection = if (target.needsConnection) resultOf { apiClient.captureConnection() } else null
    val mrConnection = connection?.getOrNull()

    supervisorScope {
      val mrFind = async { resultOf { mrLookup.findMr(context, branch, mrConnection) } }
      launch {
        val mrResult = mrFind.await().fold(
          onSuccess = { mr ->
            resultOf { CurrentBranchInfo(mr, mr?.let { mrLookup.closesIssues(it, mrConnection) }.orEmpty()) }
          },
          onFailure = { Result.failure(it) },
        )
        report(STAGE_MR, soft, mrResult.exceptionOrNull()) { "Failed to load current-branch merge request." }
        if (!unchanged(shown?.mr, mrResult)) apply(context.gitDir) { resolvedOf(it).copy(mr = mrResult) }
      }
      launch {
        val pipelineResult = resultOf { pipelineSideFor(context, target, connection, mrFind, soft) }
        if (!unchanged(shown?.pipeline, pipelineResult)) {
          apply(context.gitDir) { resolvedOf(it).copy(pipeline = pipelineResult) }
        }
      }
    }
    return TrackedHead(context.gitDir, headKeyOf(branch))
  }

  /** What the pipeline side looks for: the branch's effective ref, else HEAD's tags (only read when detached). */
  private fun pipelineTargetOf(context: RepositoryContext, branch: CurrentBranch): PipelineTarget {
    val effectiveRef = EffectiveRef.resolve(branch, context.remoteName)
    val headSha = branch.headSha
    val tags =
      if (effectiveRef == null && headSha != null) tagReader.tagsAt(File(context.gitDir), headSha) else emptyList()
    return PipelineTarget(effectiveRef, tags)
  }

  /**
   * The pipeline side (CI-05 §4.1/§4.2). Each GET settles and reports as its own stage; only a
   * failure that leaves nothing to show (capture, the branch GET without an MR candidate, the tag
   * GET) fails the side. The blocking GETs have no suspension points, so cancellation (a newer
   * refresh() or dispose() cancelling the fetch job) is observed explicitly with [ensureActive]
   * between them; the resulting [CancellationException] propagates and paints nothing.
   */
  private suspend fun pipelineSideFor(
    context: RepositoryContext,
    target: PipelineTarget,
    connection: Result<ConnectionSnapshot>?,
    mrFind: Deferred<Result<GitLabMergeRequest?>>,
    soft: Boolean,
  ): PipelineSide {
    val ref = target.effectiveRef
    // A detached HEAD queries only a single tag's pipeline: none or several tags fetch nothing.
    if (ref == null && target.tags.size != 1) return if (target.tags.isEmpty()) NoPipeline else AmbiguousTags
    val stage = if (ref != null) STAGE_PIPELINE else STAGE_TAG
    val conn = checkNotNull(connection).getOrElse { error ->
      report(stage, soft, error) { messageFor(stage) }
      throw error
    }
    val selected = if (ref != null) {
      selectForBranch(context, ref, conn, mrFind, soft)
    } else {
      val tag = target.tags.single()
      val tagResult = resultOf { pipelineService.getLatestPipelineForRef(context.projectId, tag, conn) }
      report(STAGE_TAG, soft, tagResult.exceptionOrNull()) { messageFor(STAGE_TAG) }
      tagResult.map { pipeline -> pipeline?.let { SelectedPipeline(it, PipelineKind.TAG) } }
    }
    val pipeline = selected.getOrThrow() ?: return NoPipeline
    return PipelineShown(snapshotOf(pipeline, context, conn, soft))
  }

  /**
   * The §4.1 selection: waits for the shared MR search, lists the MR's pipelines on the MR's own
   * project (`mr.projectId`), fetches the branch pipeline on the repository's project, and lets
   * [selectPipeline] pick. An MR-pipelines failure is logged and treated as "no MR candidate"; a
   * branch failure is logged even when the MR pipeline is shown instead.
   */
  private suspend fun selectForBranch(
    context: RepositoryContext,
    ref: String,
    conn: ConnectionSnapshot,
    mrFind: Deferred<Result<GitLabMergeRequest?>>,
    soft: Boolean,
  ): Result<SelectedPipeline?> {
    val mr = mrFind.await().getOrNull()
    val mrPipelines = mr?.let { resultOf { mrService.getMrPipelines(it.projectId, it.iid, conn) } }
    mrPipelines?.let { report(STAGE_MR_PIPELINES, soft, it.exceptionOrNull()) { messageFor(STAGE_MR_PIPELINES) } }
    currentCoroutineContext().ensureActive()
    val branchResult = resultOf { pipelineService.getLatestPipelineForRef(context.projectId, ref, conn) }
    report(STAGE_PIPELINE, soft, branchResult.exceptionOrNull()) { messageFor(STAGE_PIPELINE) }
    return selectPipeline(mrPipelines, branchResult)
  }

  // Fresh-payload invariant (RefreshSlots KDoc): each sub-task's payload is freshly constructed
  // by its own fetch — never a cached/reused instance — so the coordinator's identity-keyed memo
  // sees every applied settle as a change (the soft path's reuse happens by NOT applying, see
  // [unchanged]). Jobs and bridges are requested sequentially on the SELECTED pipeline's project
  // (§4.3: `pipeline.projectId`, so a fork MR's pipeline is read where it ran; context.projectId
  // only for an old GitLab that omits it), each settling as its own Result.
  private suspend fun snapshotOf(
    selected: SelectedPipeline,
    context: RepositoryContext,
    conn: ConnectionSnapshot,
    soft: Boolean,
  ): PipelineSnapshot {
    val pipeline = selected.pipeline
    val projectId = pipeline.projectId?.toString() ?: context.projectId
    // Cancelled during a synchronous pipeline GET: stop here instead of starting the
    // jobs fetch (which would otherwise run to its 15s paging deadline for nothing).
    currentCoroutineContext().ensureActive()
    val job = currentCoroutineContext()[Job]
    val isActive = { job?.isActive != false }
    val jobsResult = resultOf {
      jobService.getJobsForPipeline(projectId, pipeline.id, isActive, conn, includeRetried = true)
    }
    // Logged so the "Failed to load jobs" child has a matching Error Log entry; the
    // pipeline row itself still renders from the successfully fetched pipeline.
    report(STAGE_JOBS, soft, jobsResult.exceptionOrNull()) { "Failed to load jobs for pipeline #${pipeline.id}." }
    currentCoroutineContext().ensureActive()
    val bridgesResult = resultOf { bridgeService.getBridgesForPipeline(projectId, pipeline.id, isActive, conn) }
    report(STAGE_BRIDGES, soft, bridgesResult.exceptionOrNull()) {
      "Failed to load bridges for pipeline #${pipeline.id}."
    }
    return PipelineSnapshot(
      pipeline,
      selected.kind,
      jobsResult,
      bridgesResult,
      conn.instanceUrl,
      conn.authFingerprint,
    )
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

  /** The pipeline stages' full-path messages: never a ref, tag, branch or URL (§4.7). */
  private fun messageFor(stage: String): String =
    when (stage) {
      STAGE_MR_PIPELINES -> "Failed to load merge request pipelines."
      STAGE_TAG -> "Failed to load the tag pipeline."
      else -> "Failed to load current-branch pipeline."
    }

  private companion object {
    const val STAGE_MR = "mr"
    const val STAGE_MR_PIPELINES = "mr-pipelines"
    const val STAGE_PIPELINE = "pipeline"
    const val STAGE_TAG = "tag"
    const val STAGE_JOBS = "jobs"
    const val STAGE_BRIDGES = "bridges"
  }
}

/**
 * What one refresh's pipeline side targets: the branch's [effectiveRef], or — for a detached HEAD
 * (null ref) — the local [tags] pointing at HEAD. A connection is needed only when a GET will run.
 */
private class PipelineTarget(val effectiveRef: String?, val tags: List<String>) {
  val needsConnection: Boolean get() = effectiveRef != null || tags.size == 1
}

/**
 * Runs [block], folding any failure into the [Result] — EXCEPT [CancellationException], which always
 * propagates so a cancelled refresh paints and logs nothing (never a rendered failure). Throwable, as
 * the runCatching this replaces: the shared scope is not a SupervisorJob, so nothing else may escape.
 */
private inline fun <T> resultOf(block: () -> T): Result<T> =
  try {
    Result.success(block())
  } catch (e: CancellationException) {
    throw e
  } catch (e: Throwable) {
    Result.failure(e)
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
