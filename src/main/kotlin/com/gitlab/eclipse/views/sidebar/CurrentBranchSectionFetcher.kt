package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.JobService
import com.gitlab.eclipse.api.PipelineService
import com.gitlab.eclipse.mergerequests.CurrentBranch
import com.gitlab.eclipse.mergerequests.CurrentBranchGitReader
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
) {
  /**
   * Shared pre-step, ONCE per refresh: resolve the repository, then read the branch snapshot and
   * its effective ref, so the MR lookup and the pipeline ref work from the SAME branch state — a
   * checkout switch mid-refresh cannot make them diverge (R2-1). No repository settles the slot as
   * [NoRepository] without starting sub-tasks. Otherwise the MR and pipeline fetches run as
   * independent sub-tasks, each settling its own side of the [Resolved] payload; the other side
   * is preserved because each `update` re-reads the slot it is applied to, so the two sub-tasks
   * never race each other's halves.
   *
   * Returns the repository and HEAD this fetch looked at, or null when no repository resolved.
   */
  @Suppress("UnusedParameter") // previous/soft: the soft path's inputs, not used by the full path.
  suspend fun fetch(
    activeEditorFile: File?,
    previous: CurrentBranchSectionInput?,
    soft: Boolean,
    apply: (update: (current: CurrentBranchSectionInput?) -> CurrentBranchSectionInput) -> Unit,
  ): TrackedHead? {
    // JGit enumeration/resolution (IO). resolveNonInteractive and read never throw by
    // contract; a null context means no repository resolved non-interactively (none, or
    // several and no active editor to disambiguate) — distinct from a resolved repository
    // with no MR, which renders as "No merge request found".
    val context = resolver.resolveNonInteractive(activeEditorFile)
    if (context == null) {
      apply { NoRepository }
      return null
    }
    val branch = reader.read(File(context.gitDir))
    val effectiveRef = EffectiveRef.resolve(branch, context.remoteName)
    // Repository resolved, both sides still loading: paint the two placeholders now.
    apply { Resolved(mr = null, pipeline = null) }

    supervisorScope {
      launch {
        val mrResult = runCatching { mrLookup.lookup(context, branch) }
        mrResult.exceptionOrNull()?.let {
          errorLog("Failed to load current-branch merge request.", it)
        }
        apply { resolvedOf(it).copy(mr = mrResult) }
      }
      launch {
        // A detached HEAD has no ref to query pipelines for: a settled "no pipeline"
        // (success(null) → no row at all), not an error.
        val pipelineResult: Result<PipelineSnapshot?> =
          if (effectiveRef == null) {
            Result.success(null)
          } else {
            try {
              Result.success(fetchSnapshot(context, effectiveRef))
            } catch (e: CancellationException) {
              throw e // Propagate: a cancelled refresh paints nothing (not a failure row).
            } catch (e: Exception) {
              Result.failure(e)
            }
          }
        pipelineResult.exceptionOrNull()?.let {
          errorLog("Failed to load current-branch pipeline.", it)
        }
        apply { resolvedOf(it).copy(pipeline = pipelineResult) }
      }
    }
    return TrackedHead(context.gitDir, headKeyOf(branch))
  }

  // Fresh-payload invariant (RefreshSlots KDoc): each sub-task's payload is freshly constructed
  // by its own fetch — never a cached/reused instance — so the coordinator's identity-keyed memo
  // sees every settle as a change. The blocking fetches inside have no suspension points, so
  // cancellation (a newer refresh() or dispose() cancelling the fetch job) is observed
  // explicitly: ensureActive between the two GETs, isActive between jobs pages. The resulting
  // CancellationException must propagate (never be runCatching'd into a rendered failure) — it
  // cancels this child cleanly and paints nothing.
  private suspend fun fetchSnapshot(context: RepositoryContext, ref: String): PipelineSnapshot? {
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
    jobsResult.exceptionOrNull()?.let {
      errorLog("Failed to load jobs for pipeline #${pipeline.id}.", it)
    }
    return PipelineSnapshot(pipeline, jobsResult, connection.instanceUrl, connection.authFingerprint)
  }
}

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
