package com.gitlab.eclipse.currentbranch

import com.gitlab.eclipse.api.model.GitLabJob
import com.gitlab.eclipse.ci.CiAction
import com.gitlab.eclipse.ci.CiStatus
import com.gitlab.eclipse.ci.PipelineKind
import com.gitlab.eclipse.views.sidebar.NoPipeline
import com.gitlab.eclipse.views.sidebar.PipelineShown
import com.gitlab.eclipse.views.sidebar.PipelineSide
import com.gitlab.eclipse.views.sidebar.PipelineSnapshot
import com.gitlab.eclipse.views.sidebar.Resolved

/** Kind of one CI actions pulldown entry; the SWT layer maps each to its command. */
enum class MenuEntryKind { VIEW, RETRY, CANCEL, CREATE, ARTIFACTS, REFRESH }

/**
 * One pulldown entry. [jobs] is only set for [MenuEntryKind.ARTIFACTS]: the finished jobs of the shown pipeline,
 * in the order the fetch returned them (the SWT layer builds one submenu item per job).
 */
data class MenuEntry(
  val kind: MenuEntryKind,
  val label: String,
  val enabled: Boolean,
  val jobs: List<GitLabJob> = emptyList(),
)

/**
 * Pure model of the CI actions pulldown (status-bar design §4.6), built from a window's [CurrentBranchSnapshot].
 * Retry / Cancel follow the CI-01 eligibility (`CiStatus.contextAction`) and need a `projectId` (R12); View needs a
 * `webUrl`. Download artifacts lists finished jobs only, since `GitLabJob` carries no artifacts field (R14).
 */
object PipelineMenuModel {
  private val FINISHED_JOB_STATUSES = setOf("success", "failed", "canceled")

  fun build(snapshot: CurrentBranchSnapshot): List<MenuEntry> {
    val side = (snapshot.input as? Resolved)?.pipeline?.getOrNull()
    val entries = mutableListOf<MenuEntry>()
    if (side is PipelineShown) entries += shownPipelineEntries(side.snapshot)
    if (canCreate(side)) entries += MenuEntry(MenuEntryKind.CREATE, "Create new pipeline…", true)
    if (side is PipelineShown) artifactsEntry(side.snapshot)?.let { entries += it }
    entries += MenuEntry(MenuEntryKind.REFRESH, "Refresh", true)
    return entries
  }

  private fun shownPipelineEntries(shown: PipelineSnapshot): List<MenuEntry> {
    val pipeline = shown.pipeline
    val action = CiStatus.contextAction(pipeline.status)
    val writable = pipeline.projectId != null
    return listOf(
      MenuEntry(MenuEntryKind.VIEW, "View pipeline in GitLab", pipeline.webUrl != null),
      MenuEntry(MenuEntryKind.RETRY, "Retry pipeline", writable && action == CiAction.RETRYABLE),
      MenuEntry(MenuEntryKind.CANCEL, "Cancel pipeline", writable && action == CiAction.CANCELLABLE),
    )
  }

  // Create is offered on a branch (shown or without a pipeline), never for a tag.
  private fun canCreate(side: PipelineSide?): Boolean =
    side == NoPipeline || (side is PipelineShown && side.snapshot.kind != PipelineKind.TAG)

  private fun artifactsEntry(shown: PipelineSnapshot): MenuEntry? {
    val jobs = shown.jobsResult.getOrNull().orEmpty().filter { it.status in FINISHED_JOB_STATUSES }
    return if (jobs.isEmpty()) null else MenuEntry(MenuEntryKind.ARTIFACTS, "Download artifacts", true, jobs)
  }
}
