package com.gitlab.eclipse.ci

import com.gitlab.eclipse.api.model.GitLabPipeline
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

private const val BRANCH_FAILED = "branch failed"
private const val NONE = "none"

private fun p(id: Long, projectId: Long? = 1L) = GitLabPipeline(id = id, projectId = projectId)

/** MR-pipelines fetch that succeeded with the given elements. */
private fun mr(vararg pipelines: GitLabPipeline): Result<List<GitLabPipeline>> = Result.success(pipelines.toList())

private val mrFailed: Result<List<GitLabPipeline>> = Result.failure(RuntimeException("403"))
private val branchFailed: Result<GitLabPipeline?> = Result.failure(IllegalStateException(BRANCH_FAILED))

private fun branchOf(pipeline: GitLabPipeline?): Result<GitLabPipeline?> = Result.success(pipeline)

/** [expected] is `B<id>` / `M<id>` for a selection, [NONE] for success(null) or [BRANCH_FAILED] for a failure. */
private data class SelectionCase(
  val name: String,
  val mr: Result<List<GitLabPipeline>>?,
  val branch: Result<GitLabPipeline?>,
  val expected: String,
)

private fun describeResult(result: Result<SelectedPipeline?>): String {
  val failure = result.exceptionOrNull()
  val selected = result.getOrNull()
  return when {
    failure != null -> failure.message.orEmpty()
    selected == null -> NONE
    selected.kind == PipelineKind.BRANCH -> "B${selected.pipeline.id}"
    selected.kind == PipelineKind.MERGE_REQUEST -> "M${selected.pipeline.id}"
    else -> "T${selected.pipeline.id}"
  }
}

class PipelineSelectionTest : DescribeSpec({
  describe("selectPipeline (design 4.1 table)") {
    withData(
      nameFn = { it.name },
      SelectionCase("no MR, branch ok -> branch", null, branchOf(p(5)), "B5"),
      SelectionCase("no MR, branch has none -> none", null, branchOf(null), NONE),
      SelectionCase("no MR, branch failed -> failure", null, branchFailed, BRANCH_FAILED),
      SelectionCase("MR newer than branch -> MR", mr(p(9)), branchOf(p(5)), "M9"),
      SelectionCase("branch newer than MR -> branch", mr(p(4)), branchOf(p(5)), "B5"),
      SelectionCase("tie on id -> branch", mr(p(5)), branchOf(p(5)), "B5"),
      SelectionCase("MR only, branch none -> MR", mr(p(9)), branchOf(null), "M9"),
      SelectionCase("empty MR list, branch ok -> branch", mr(), branchOf(p(5)), "B5"),
      SelectionCase("empty MR list, branch none -> none", mr(), branchOf(null), NONE),
      SelectionCase("MR failed, branch ok -> branch", mrFailed, branchOf(p(5)), "B5"),
      SelectionCase("MR failed, branch none -> none", mrFailed, branchOf(null), NONE),
      SelectionCase("MR failed, branch failed -> failure", mrFailed, branchFailed, BRANCH_FAILED),
      SelectionCase("MR ok, branch failed -> MR", mr(p(9)), branchFailed, "M9"),
      SelectionCase("empty MR list, branch failed -> failure", mr(), branchFailed, BRANCH_FAILED),
      SelectionCase("max id of several MR pipelines", mr(p(3), p(12), p(7)), branchOf(p(5)), "M12"),
      SelectionCase("MR element without projectId ignored", mr(p(99, null), p(7)), branchOf(p(5)), "M7"),
      SelectionCase("only MR elements without projectId -> branch", mr(p(99, null)), branchOf(p(5)), "B5"),
      SelectionCase("no-projectId MR only, branch failed -> failure", mr(p(99, null)), branchFailed, BRANCH_FAILED),
    ) { c ->
      describeResult(selectPipeline(c.mr, c.branch)) shouldBe c.expected
    }
  }
})
