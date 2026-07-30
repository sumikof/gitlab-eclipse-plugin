package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.model.GitLabJob
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
