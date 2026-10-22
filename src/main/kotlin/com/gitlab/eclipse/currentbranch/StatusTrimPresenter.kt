package com.gitlab.eclipse.currentbranch

import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.ci.CiStatus
import com.gitlab.eclipse.ci.PipelineKind
import com.gitlab.eclipse.mergerequests.CurrentBranchInfo
import com.gitlab.eclipse.views.sidebar.AmbiguousTags
import com.gitlab.eclipse.views.sidebar.NoPipeline
import com.gitlab.eclipse.views.sidebar.NoRepository
import com.gitlab.eclipse.views.sidebar.PipelineShown
import com.gitlab.eclipse.views.sidebar.PipelineSide
import com.gitlab.eclipse.views.sidebar.Resolved

/**
 * Pure mapping from a window's [CurrentBranchSnapshot] to the [TrimModel] (status-bar design §4.5, mirroring
 * VS Code `status_bar.ts`). A side that is still pending keeps what was rendered for it before ([previous]), so a
 * refresh over a settled state never flickers back to the loading texts.
 */
object StatusTrimPresenter {
  private const val LOADING_PIPELINE = "Fetching pipeline…"
  private const val LOADING_MR = "Finding MR…"

  private fun loadingItem(text: String) = TrimItemModel(TrimIcon.INFO_LOADING, text, text, TrimAction.None)

  private val LOADING_PIPELINE_ITEM = loadingItem(LOADING_PIPELINE)
  private val LOADING_MR_ITEM = loadingItem(LOADING_MR)

  fun present(snapshot: CurrentBranchSnapshot, previous: TrimModel?): TrimModel =
    when (val input = snapshot.input) {
      null -> previous ?: loading()
      NoRepository -> TrimModel.HIDDEN
      is Resolved -> presentResolved(input, previous)
    }

  private fun loading() = TrimModel(LOADING_PIPELINE_ITEM, LOADING_MR_ITEM, null)

  private fun presentResolved(input: Resolved, previous: TrimModel?): TrimModel {
    val pipelineResult = input.pipeline
    if (pipelineResult == null && input.mr == null && previous != null) return previous

    val side = pipelineResult?.getOrNull()
    // Ambiguous tags and a tag pipeline both mean there is no branch to show an MR for.
    if (side == AmbiguousTags) return TrimModel.HIDDEN

    val pipeline = when {
      pipelineResult == null -> previous?.pipeline ?: LOADING_PIPELINE_ITEM
      side == null -> null // pipeline side failed
      else -> pipelineItem(side)
    }
    if (side is PipelineShown && side.snapshot.kind == PipelineKind.TAG) return TrimModel(pipeline, null, null)
    return withMr(pipeline, input.mr, previous)
  }

  private fun withMr(pipeline: TrimItemModel?, mrResult: Result<CurrentBranchInfo>?, previous: TrimModel?): TrimModel =
    when {
      mrResult == null -> TrimModel(pipeline, previous?.mr ?: LOADING_MR_ITEM, previous?.issue)
      mrResult.isFailure -> TrimModel(pipeline, null, null)
      else -> mrModel(pipeline, mrResult.getOrThrow())
    }

  private fun pipelineItem(side: PipelineSide): TrimItemModel? =
    when (side) {
      is PipelineShown -> {
        val pipeline = side.snapshot.pipeline
        TrimItemModel(
          icon = iconFor(pipeline.status),
          text = "Pipeline ${CiStatus.displayName(pipeline.status)}",
          tooltip = "Pipeline #${pipeline.id}",
          action = TrimAction.ShowPipelineMenu,
        )
      }
      NoPipeline -> TrimItemModel(TrimIcon.NO_PIPELINE, "No pipeline", "No pipeline", TrimAction.ShowPipelineMenu)
      AmbiguousTags -> null
    }

  private fun mrModel(pipeline: TrimItemModel?, info: CurrentBranchInfo): TrimModel {
    val mr = info.mr ?: return TrimModel(
      pipeline,
      TrimItemModel(TrimIcon.MR_CREATE, "Create MR", "Create merge request", TrimAction.CreateMergeRequest),
      null,
    )
    val mrItem = TrimItemModel(
      icon = if (mr.draft) TrimIcon.MR_DRAFT else TrimIcon.MR_OPEN,
      text = "!${mr.iid}",
      tooltip = mr.title,
      action = TrimAction.OpenUrl(mr.webUrl),
    )
    return TrimModel(pipeline, mrItem, issueItem(info.closesIssues))
  }

  private fun issueItem(issues: List<GitLabIssue>): TrimItemModel {
    val first = issues.firstOrNull()
      ?: return TrimItemModel(TrimIcon.ISSUE, "No issue", "No closing issue", TrimAction.None)
    val more = issues.size - 1
    return TrimItemModel(
      icon = TrimIcon.ISSUE,
      text = "#${first.iid}",
      tooltip = if (more > 0) "${first.title} and $more more" else first.title,
      action = TrimAction.OpenUrl(first.webUrl),
    )
  }

  /** Every status of `ci/CiStatus.kt` maps to a dedicated icon; anything else falls back to [TrimIcon.UNKNOWN]. */
  internal fun iconFor(status: String?): TrimIcon =
    when (status) {
      "success" -> TrimIcon.SUCCESS
      "failed" -> TrimIcon.FAILED
      "running" -> TrimIcon.RUNNING
      "pending", "created", "preparing", "waiting_for_resource", "scheduled" -> TrimIcon.PENDING
      "canceled", "canceling" -> TrimIcon.CANCELED
      "skipped" -> TrimIcon.SKIPPED
      "manual" -> TrimIcon.MANUAL
      else -> TrimIcon.UNKNOWN
    }
}
