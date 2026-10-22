package com.gitlab.eclipse.currentbranch

import com.gitlab.eclipse.api.model.GitLabJob
import com.gitlab.eclipse.ci.actions.ARTIFACTS_LAUNCH_FAILED_MESSAGE
import com.gitlab.eclipse.ci.actions.ARTIFACTS_UNAVAILABLE_MESSAGE
import com.gitlab.eclipse.ci.actions.PipelineWrite
import com.gitlab.eclipse.ci.actions.PipelineWriteAction
import com.gitlab.eclipse.ci.actions.buildArtifactsDownloadUrl
import com.gitlab.eclipse.views.sidebar.PipelineShown
import com.gitlab.eclipse.views.sidebar.PipelineSnapshot
import com.gitlab.eclipse.views.sidebar.Resolved

internal const val VIEW_PIPELINE_FAILED_MESSAGE = "Couldn't open the pipeline in your browser."

/**
 * One entry of the trim's CI actions pulldown, ready to render: [action] runs it ([run] only while [enabled]);
 * [children] make it a submenu (Download artifacts).
 */
data class PipelineMenuItem(
  val label: String,
  val enabled: Boolean,
  val action: () -> Unit,
  val children: List<PipelineMenuItem> = emptyList(),
) {
  fun run() {
    if (enabled) action()
  }
}

/**
 * The trim's CI actions pulldown (status-bar design §4.6): [PipelineMenuModel]'s entries for a snapshot, each bound
 * to what it does. SWT-free; the trim renders [itemsFor] of its window's last settled snapshot (so a pipeline kept on
 * screen during a refresh still offers its actions) and supplies the ports:
 *
 * - View / artifacts: [openUrl] on the snapshot's own URLs (no re-fetch); a failed launch notifies without the URL.
 * - Retry / Cancel: [writePipeline] pinned to the connection the snapshot was fetched over (R13: the pinned write of
 *   the sidebar commands, without a sidebar selection); its success runs [requestRefresh] for this window.
 * - Create new pipeline: [createPipeline] (the existing command, with its confirmation).
 * - Refresh: [refresh] (the window's full refresh).
 */
class PipelineMenuActions(
  private val openUrl: (String) -> Boolean,
  private val writePipeline: (PipelineWrite, () -> Unit) -> Unit,
  private val createPipeline: () -> Unit,
  private val refresh: () -> Unit,
  private val requestRefresh: () -> Unit,
  private val notify: (String) -> Unit,
) {
  fun itemsFor(snapshot: CurrentBranchSnapshot): List<PipelineMenuItem> {
    val shown = ((snapshot.input as? Resolved)?.pipeline?.getOrNull() as? PipelineShown)?.snapshot
    return PipelineMenuModel.build(snapshot).map { entry ->
      when (entry.kind) {
        MenuEntryKind.VIEW -> item(entry) { shown?.pipeline?.webUrl?.let { open(it, VIEW_PIPELINE_FAILED_MESSAGE) } }
        MenuEntryKind.RETRY -> item(entry) { shown?.let { write(it, PipelineWriteAction.RETRY) } }
        MenuEntryKind.CANCEL -> item(entry) { shown?.let { write(it, PipelineWriteAction.CANCEL) } }
        MenuEntryKind.CREATE -> item(entry, createPipeline)
        MenuEntryKind.ARTIFACTS -> PipelineMenuItem(entry.label, entry.enabled, {}, entry.jobs.map(::artifactsItem))
        MenuEntryKind.REFRESH -> item(entry, refresh)
      }
    }
  }

  private fun item(entry: MenuEntry, action: () -> Unit) = PipelineMenuItem(entry.label, entry.enabled, action)

  private fun write(shown: PipelineSnapshot, action: PipelineWriteAction) {
    val projectId = shown.pipeline.projectId ?: return // R12: the entry is disabled then
    val write = PipelineWrite(
      action = action,
      projectId = projectId,
      pipelineId = shown.pipeline.id,
      sourceInstanceUrl = shown.sourceInstanceUrl,
      sourceAuthFingerprint = shown.sourceAuthFingerprint,
    )
    writePipeline(write, requestRefresh)
  }

  private fun artifactsItem(job: GitLabJob) =
    PipelineMenuItem(job.name ?: "Job #${job.id}", true, {
      val url = buildArtifactsDownloadUrl(job.webUrl)
      if (url == null) notify(ARTIFACTS_UNAVAILABLE_MESSAGE) else open(url, ARTIFACTS_LAUNCH_FAILED_MESSAGE)
    })

  private fun open(url: String, failure: String) {
    if (!openUrl(url)) notify(failure)
  }
}
