package com.gitlab.eclipse.lsp.diagnostics

import com.gitlab.eclipse.utils.logger
import org.eclipse.core.resources.IFile
import org.eclipse.core.resources.IMarker
import org.eclipse.core.resources.IResource
import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.core.resources.WorkspaceJob
import org.eclipse.core.runtime.CoreException
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.Status
import org.eclipse.core.runtime.jobs.ISchedulingRule
import org.eclipse.core.runtime.jobs.MultiRule
import org.eclipse.lsp4j.Diagnostic

private const val APPLY_JOB = "GitLab diagnostics"
private const val CLEANUP_JOB = "GitLab diagnostics cleanup"

/**
 * A marker whose counter cannot be read is older than anything we could publish now, so it is
 * always treated as a leftover and removed.
 */
private const val OLDER_THAN_ANY_GENERATION = -1L

/**
 * The workspace only accepts `null`, `String`, `Boolean` and `Integer` marker attribute values, so
 * generation and epoch are stored as decimal strings (see [DiagnosticMarkerAttributes.of]) and have
 * to be read back through the `String` overload. They are never narrowed to `Int`: both counters are
 * `Long` everywhere else and truncating them would silently merge two different generations.
 */
private fun IMarker.longAttribute(name: String): Long =
  getAttribute(name, "").toLongOrNull() ?: OLDER_THAN_ANY_GENERATION

private fun IMarker.markerGeneration(): Long =
  longAttribute(DiagnosticMarkerAttributes.ATTR_GENERATION)

private fun IMarker.markerEpoch(): Long = longAttribute(DiagnosticMarkerAttributes.ATTR_EPOCH)

private fun workspaceMarkers(): Array<IMarker> = ResourcesPlugin.getWorkspace().root
  .findMarkers(DiagnosticMarkerAttributes.TYPE, false, IResource.DEPTH_INFINITE)

private fun fileMarkers(file: IFile): Array<IMarker> =
  file.findMarkers(DiagnosticMarkerAttributes.TYPE, false, IResource.DEPTH_ZERO)

/**
 * Deleting markers is best effort: the workspace can refuse at any point (closed project, marker
 * already gone) and neither the caller nor the job it runs in should fail because of it.
 */
private fun deleteMatching(
  logger: ILog,
  markers: () -> Array<IMarker>,
  predicate: (IMarker) -> Boolean
) {
  try {
    markers().filter(predicate).forEach { it.delete() }
  } catch (e: CoreException) {
    logger.warn("Failed to delete diagnostics markers.", e)
  }
}

/**
 * Publishes language server diagnostics as workspace markers.
 *
 * Ordering is delegated to the platform's scheduling rules rather than to the UI thread: an apply
 * job locks the files it writes and every clean up job locks the workspace root, which conflicts
 * with every file rule. The platform therefore serialises applies against clean ups for us.
 *
 * Clean up is always selective. A clean up that starts late must not remove markers that a newer
 * decision has published in the meantime, so it deletes by epoch or by source plus watermark.
 */
class DiagnosticMarkerService {
  private val logger by lazy { logger<DiagnosticMarkerService>() }

  fun apply(uriKey: String, diagnostics: List<Diagnostic>, generation: Long, epoch: Long) {
    val files = DiagnosticFileResolver.resolve(uriKey)
    if (files.isEmpty()) return

    val job = object : WorkspaceJob(APPLY_JOB) {
      override fun runInWorkspace(monitor: IProgressMonitor?): IStatus {
        applyNow(uriKey, files, diagnostics, generation, epoch)
        return Status.OK_STATUS
      }
    }
    val rules: List<ISchedulingRule> = files
    job.rule = MultiRule.combine(rules.toTypedArray())
    job.isSystem = true
    job.schedule()
  }

  /**
   * Runs inside the apply job. The registry is re-checked here, not when the job was scheduled: by
   * now this batch may have been overtaken by a newer one or the language server may have been
   * restarted, and in both cases these diagnostics must not reach the workspace.
   */
  internal fun applyNow(
    uriKey: String,
    files: List<IFile>,
    diagnostics: List<Diagnostic>,
    generation: Long,
    epoch: Long
  ) {
    if (!DiagnosticGenerationRegistry.shouldApply(uriKey, generation, epoch)) return
    files.forEach { replaceIn(it, diagnostics, generation, epoch) }
  }

  /**
   * Two phase replacement. The new generation is created first and the previous one is only removed
   * once every marker was created; if creation fails half way the new generation is removed instead.
   * What an observer can see is therefore always one complete generation, never a mixture.
   *
   * The switch phase itself is not compensated: a failure there leaves both generations visible,
   * which is preferable to dropping findings.
   */
  internal fun replaceIn(file: IFile, diagnostics: List<Diagnostic>, generation: Long, epoch: Long) {
    try {
      diagnostics.forEach { diagnostic ->
        file.createMarker(DiagnosticMarkerAttributes.TYPE)
          .setAttributes(DiagnosticMarkerAttributes.of(diagnostic, generation, epoch))
      }
    } catch (e: CoreException) {
      logger.warn("Failed to create diagnostics markers; rolling back this generation.", e)
      deleteMatching(logger, { fileMarkers(file) }) { it.markerGeneration() == generation }
      return
    }
    deleteMatching(logger, { fileMarkers(file) }) { it.markerGeneration() != generation }
  }

  /**
   * Called when a source is switched off. Markers published after [watermark] belong to a newer
   * decision than the one that triggered this clean up, so they are kept.
   */
  fun deleteMarkersBySource(source: String, watermark: Long) =
    scheduleRootJob { deleteBySource(source, watermark) }

  internal fun deleteBySource(source: String, watermark: Long) =
    deleteMatching(logger, ::workspaceMarkers) { marker ->
      val sameSource = marker.getAttribute(DiagnosticMarkerAttributes.ATTR_SOURCE, "") == source
      sameSource && marker.markerGeneration() <= watermark
    }

  /**
   * Called when the language server stopped. Markers of the current epoch survive, so a clean up
   * that runs late cannot remove what the next connection has already published.
   */
  fun deleteMarkersNotInEpoch(epoch: Long) = scheduleRootJob { deleteNotInEpoch(epoch) }

  internal fun deleteNotInEpoch(epoch: Long) =
    deleteMatching(logger, ::workspaceMarkers) { it.markerEpoch() != epoch }

  /** Called when the bundle stops. Everything goes, whatever epoch it belongs to. */
  fun deleteAllMarkers() = scheduleRootJob { deleteAll() }

  internal fun deleteAll() {
    try {
      ResourcesPlugin.getWorkspace().root
        .deleteMarkers(DiagnosticMarkerAttributes.TYPE, false, IResource.DEPTH_INFINITE)
    } catch (e: CoreException) {
      logger.warn("Failed to clean up diagnostics markers.", e)
    }
  }

  private fun scheduleRootJob(body: () -> Unit) {
    val job = object : WorkspaceJob(CLEANUP_JOB) {
      override fun runInWorkspace(monitor: IProgressMonitor?): IStatus {
        body()
        return Status.OK_STATUS
      }
    }
    // The workspace root conflicts with every file rule, which is what serialises clean ups
    // against the apply jobs.
    job.rule = ResourcesPlugin.getWorkspace().root
    job.isSystem = true
    job.schedule()
  }
}
