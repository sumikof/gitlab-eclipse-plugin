package com.gitlab.eclipse.ci.actions

import com.gitlab.eclipse.ci.joblog.readAuditMessage
import com.gitlab.eclipse.navigation.BrowserLauncher
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.sidebar.JobNode
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent

internal const val ARTIFACTS_UNAVAILABLE_MESSAGE = "This job has no downloadable artifacts, or its URL is unavailable."
internal const val ARTIFACTS_LAUNCH_FAILED_MESSAGE = "Couldn't open the artifacts download in your browser."

/**
 * Opens the artifacts-download URL of the job selected in the GitLab sidebar in the external browser
 * (design §8.2). The thinnest possible handler: build the URL ([buildArtifactsDownloadUrl]) and open it
 * ([BrowserLauncher.openChecked]) — entirely on the UI thread, no network request, no token sent (the
 * browser's existing GitLab session handles auth), identical to the VSCode extension.
 */
@Suppress("unused")
class DownloadArtifactsHandler(
  private val browserLauncher: BrowserLauncher = BrowserLauncher(),
) : AbstractHandler() {
  private val log = logger<DownloadArtifactsHandler>()

  override fun execute(event: ExecutionEvent): Any? {
    val node = selectedSidebarNode<JobNode>(event)
    if (node == null) {
      NotificationUtils.show(SELECT_JOB_MESSAGE)
      return null
    }
    val url = buildArtifactsDownloadUrl(node.job.webUrl)
    if (url == null) {
      log.error(
        readAuditMessage(
          "downloadArtifacts",
          node.sourceInstanceUrl,
          node.projectId,
          node.job.id,
          "invalidWebUrl",
        ),
      )
      NotificationUtils.show(ARTIFACTS_UNAVAILABLE_MESSAGE)
      return null
    }
    if (!browserLauncher.openChecked(url)) {
      log.error(
        readAuditMessage(
          "downloadArtifacts",
          node.sourceInstanceUrl,
          node.projectId,
          node.job.id,
          "launchFailed",
        ),
      )
      NotificationUtils.show(ARTIFACTS_LAUNCH_FAILED_MESSAGE)
    }
    return null
  }
}
