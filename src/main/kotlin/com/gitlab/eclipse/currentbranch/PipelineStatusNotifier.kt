package com.gitlab.eclipse.currentbranch

import com.gitlab.eclipse.ci.CiStatus
import com.gitlab.eclipse.views.sidebar.PipelineShown
import com.gitlab.eclipse.views.sidebar.Resolved

/** Opens the notification popup; [gate] is re-checked when it is about to open (production: `ActionNotifications`). */
fun interface PipelineNotificationPopup {
  fun show(title: String, message: String, links: List<Pair<String, () -> Unit>>, gate: () -> Boolean)
}

/**
 * CI-03 (status-bar design §4.7): a popup when the current branch's pipeline changes status, for one window. A
 * subscriber of the window's state, fed by every apply: a shown pipeline is an observation for the window's
 * [PipelineChangeDetector] (the first observation of a pipeline is silent, D3); a change is shown only while the
 * preference is on ([isEnabled], read at that moment — no restart, no replay of earlier transitions) and once across
 * windows ([dedupe], process-wide). Anything else (placeholders, failures, no pipeline, no repository) is not an
 * observation and leaves the memory as it is.
 *
 * [show] opens the popup asynchronously (production: `ActionNotifications.show`, an `asyncExec`), so it appears after
 * the trim rendered the same apply; its gate re-checks the preference and that the window is still alive. The "View
 * in GitLab" link opens the pipeline's URL through [openUrl] and touches no window state. UI thread only; never logs.
 */
class PipelineStatusNotifier(
  private val isEnabled: () -> Boolean,
  private val isWindowAlive: () -> Boolean,
  private val dedupe: PipelineNotificationDedupe,
  private val show: PipelineNotificationPopup,
  private val openUrl: (String) -> Unit,
  private val detector: PipelineChangeDetector = PipelineChangeDetector(),
) : CurrentBranchListener {
  override fun onApplied(id: Long, snapshot: CurrentBranchSnapshot) {
    val gitDir = snapshot.gitDir ?: return
    val shown = ((snapshot.input as? Resolved)?.pipeline?.getOrNull() as? PipelineShown)?.snapshot ?: return
    val pipeline = shown.pipeline
    if (!detector.observe(gitDir, pipeline.id, pipeline.status)) return
    if (!isEnabled()) return
    if (!dedupe.shouldShow(shown.sourceInstanceUrl, pipeline.projectId, pipeline.id, pipeline.status)) return
    val ref = pipeline.ref?.let { " ($it)" }.orEmpty()
    val links = pipeline.webUrl?.let { url -> listOf<Pair<String, () -> Unit>>("View in GitLab" to { openUrl(url) }) }
    show.show(TITLE, "Pipeline ${CiStatus.displayName(pipeline.status)}$ref", links.orEmpty()) {
      isEnabled() && isWindowAlive()
    }
  }

  private companion object {
    const val TITLE = "GitLab"
  }
}
