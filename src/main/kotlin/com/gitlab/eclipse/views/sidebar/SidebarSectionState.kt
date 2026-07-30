package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.api.model.GitLabJob
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.api.model.GitLabPipeline
import com.gitlab.eclipse.mergerequests.CurrentBranchInfo

/**
 * A pipeline paired with its (possibly still-failed) jobs fetch, mirroring the
 * `(pipeline, jobsResult)` pair [SidebarViewModel.buildPipelineNode] already consumes.
 */
data class PipelineSnapshot(val pipeline: GitLabPipeline, val jobsResult: Result<List<GitLabJob>>)

/**
 * Input to [SidebarViewModel.buildCurrentBranchSection]'s new overload (design doc §6.6):
 * the MR lookup and the pipeline lookup are independent async tasks (Task 9 wires each to its
 * own coroutine), so this type lets the section be (re)built from whichever of the two has
 * settled — a slow/failed pipeline fetch must not hide an already-resolved MR, and vice versa.
 */
sealed interface CurrentBranchSectionInput

/** No repository is resolved for the workspace (or none is selected yet). */
object NoRepository : CurrentBranchSectionInput

/**
 * A repository is resolved; [mr] and [pipeline] are each `null` while their corresponding fetch
 * is still in flight, or the settled [Result] once it completes. `pipeline` further distinguishes
 * "still loading" (`null`) from "loaded, and there is no current pipeline" (`Result.success(null)`)
 * from "loaded, here it is" (`Result.success(PipelineSnapshot)`).
 */
data class Resolved(
  val mr: Result<CurrentBranchInfo>?,
  val pipeline: Result<PipelineSnapshot?>?,
) : CurrentBranchSectionInput

/**
 * One async fetch's outcome slot within a refresh generation (design doc §6.6/§9): [Pending]
 * until the fetch settles, then [Settled] with whatever value it produced. Distinct from
 * `Result` — a settled slot may well hold a failed `Result`; the slot only says "this unit
 * has reported in", so [SidebarRefreshCoordinator] can compose the units that have settled
 * without gating on the ones that haven't.
 */
sealed interface Slot<out T> {
  object Pending : Slot<Nothing>
  data class Settled<T>(val value: T) : Slot<T>
}

/**
 * All top-level fetch slots of one refresh generation (design doc §9/R7-R9). Task 9's view
 * creates a fresh instance per refresh with a strictly increasing [generation], mutates the
 * slots on the UI thread as each unit's fetch settles, and re-composes via
 * [SidebarRefreshCoordinator.compose] after each mutation. A superseded generation's instance
 * is simply abandoned — the coordinator resets its memo when [generation] changes, so a stale
 * generation's values can never leak into a newer compose.
 */
class RefreshSlots(val generation: Long) {
  var assigned: Slot<Pair<Result<List<GitLabIssue>>, Result<List<GitLabMergeRequest>>>> = Slot.Pending
  var currentBranch: Slot<CurrentBranchSectionInput> = Slot.Pending
}
