package com.gitlab.eclipse.ci

import com.gitlab.eclipse.api.model.GitLabPipeline

/** Which pipeline list a [SelectedPipeline] came from (drives the label: TAG is `Tag Pipeline #<id>`). */
enum class PipelineKind { BRANCH, MERGE_REQUEST, TAG }

data class SelectedPipeline(val pipeline: GitLabPipeline, val kind: PipelineKind)

/**
 * Picks the pipeline to show for the current branch (design 4.1 table).
 *
 * [mrPipelines] is `null` when there is no MR or the MR lookup failed (nothing was fetched); a failed
 * MR-pipelines fetch is treated exactly the same: both mean "no MR candidate", so MR-side problems never
 * hide the branch pipeline. The MR candidate is the highest-id element that has a `projectId`; elements
 * without one are ignored because their jobs/bridges could not be addressed (design 4.3).
 *
 * Candidates are compared by `id`, not `iid`: with fork MRs the two lists can belong to different projects,
 * where `iid` is not comparable but the instance-wide `id` is. A tie goes to the branch pipeline. A failed
 * [branch] only surfaces as a failure when there is no MR candidate to fall back on (VS Code parity).
 */
fun selectPipeline(
  mrPipelines: Result<List<GitLabPipeline>>?,
  branch: Result<GitLabPipeline?>,
): Result<SelectedPipeline?> {
  val mr = mrPipelines?.getOrNull()
    ?.filter { it.projectId != null }
    ?.maxByOrNull { it.id }
  val mrSelected = mr?.let { SelectedPipeline(it, PipelineKind.MERGE_REQUEST) }
  val branchFailure = branch.exceptionOrNull()
  if (branchFailure != null) {
    return if (mrSelected != null) Result.success(mrSelected) else Result.failure(branchFailure)
  }
  val branchPipeline = branch.getOrNull()
  val branchSelected = branchPipeline?.let { SelectedPipeline(it, PipelineKind.BRANCH) }
  return Result.success(
    when {
      mr == null || branchPipeline == null -> branchSelected ?: mrSelected
      mr.id > branchPipeline.id -> mrSelected
      else -> branchSelected
    },
  )
}
