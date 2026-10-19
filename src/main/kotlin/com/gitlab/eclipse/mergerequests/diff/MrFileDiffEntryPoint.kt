package com.gitlab.eclipse.mergerequests.diff

import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.mergerequests.RepositoryContextResolver
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.sidebar.ChangedFileNode
import java.io.File

/**
 * The one place the sidebar and the command open MR file diffs from, and the bundle lifecycle of
 * the feature. A single [MrFileDiffController] serves every open, so a newer snapshot of a file can
 * cancel the older load. UI thread only.
 */
object MrFileDiffEntryPoint {
  private val controllerHolder = lazy { MrFileDiffController(productionLoader(), EclipseMrDiffPorts()) }
  private val controller by controllerHolder

  /** UI thread. Opens the diff of [node], or explains why it cannot. Never throws. */
  fun open(node: ChangedFileNode) {
    try {
      controller.open(diffRequestOf(node))
    } catch (e: Exception) {
      val type = e.javaClass.name
      runCatching {
        logger<MrFileDiffEntryPoint>().warn("MR file diff open failed: outcome=GENERIC exceptionType=$type")
      }
      NotificationUtils.show(MrDiffMessages.message(DiffFailure.GENERIC))
    }
  }

  /**
   * Bundle stop, UI thread: no load may deliver any more, the running loads are cancelled, and the
   * open diff editors are closed (unless the workbench is closing). Each part is contained, so a
   * failing one does not skip the next. Never throws.
   */
  fun onStop(
    deactivateController: () -> Unit = ::deactivateControllerIfCreated,
    cancelJobs: () -> Unit = MrFileDiffJobs::cancelAll,
    closeEditors: () -> Unit = MrCompareOpener::closeAll,
  ) {
    MrFileDiffRegistry.onDeactivate()
    contained("controller", deactivateController)
    contained("cancel", cancelJobs)
    contained("close", closeEditors)
  }

  /** Drops the controller's job handles; never builds a controller that was not used yet. */
  private fun deactivateControllerIfCreated() {
    if (controllerHolder.isInitialized()) controller.onDeactivate()
  }

  /** Bundle start, UI thread: loads may deliver again; generations from before the stop never do. */
  fun onStart() {
    MrFileDiffRegistry.onActivate()
  }

  private fun contained(step: String, block: () -> Unit) {
    try {
      block()
    } catch (e: Exception) {
      val type = e.javaClass.name
      runCatching {
        logger<MrFileDiffEntryPoint>().warn("MR file diff stop step $step skipped: exceptionType=$type")
      }
    }
  }

  /**
   * Background-only collaborators, built lazily on the first open: the connection is captured, and
   * repositories are enumerated and read, only inside the Job.
   */
  private fun productionLoader(): MrFileDiffLoader {
    val api = service<GitLabApiClient>()
    val local = LocalBlobReader { RepositoryContextResolver().candidateContexts().map { File(it.gitDir) } }
    return MrFileDiffLoader(
      captureConnection = { api.captureConnection() },
      source = HybridMrFileContentSource(local, RawFileFetcher(api)),
    )
  }
}

/** The validated diff request for a sidebar changed-file node. */
internal fun diffRequestOf(node: ChangedFileNode): MrFileDiffRequest.Validated =
  MrFileDiffRequest.from(node.diffSource, node.oldPath, node.newPath, node.changeType, node.aMode, node.bMode)
