package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.ci.CiAction
import com.gitlab.eclipse.ci.CiStatus
import org.eclipse.core.expressions.PropertyTester

/**
 * Status-dependent visibility for the sidebar pipeline/job context-menu actions (design §8.6).
 * A node with a null projectId can never act (write routing needs it), so every property is
 * false then. A [BridgeNode] (CI-06 §4.6) affords retry and play like a job, never cancel: GitLab's
 * cancel endpoint answers 404 for a bridge. Downstream pipeline nodes afford nothing.
 */
class CiActionPropertyTester : PropertyTester() {
  override fun test(receiver: Any?, property: String?, args: Array<out Any?>?, expectedValue: Any?): Boolean =
    when (property) {
      "canRetry" -> canRetry(receiver)
      "canCancel" -> canCancel(receiver)
      "canPlay" -> canPlay(receiver)
      else -> false
    }

  private fun canRetry(receiver: Any?): Boolean = when (receiver) {
    is PipelineNode -> receiver.projectId != null && receiver.canRetry
    is JobNode -> receiver.projectId != null && jobAction(receiver) == CiAction.RETRYABLE
    is BridgeNode -> receiver.projectId != null && bridgeAction(receiver) == CiAction.RETRYABLE
    else -> false
  }

  private fun canCancel(receiver: Any?): Boolean = when (receiver) {
    is PipelineNode -> receiver.projectId != null && receiver.canCancel
    is JobNode -> receiver.projectId != null && jobAction(receiver) == CiAction.CANCELLABLE
    else -> false
  }

  private fun canPlay(receiver: Any?): Boolean = when (receiver) {
    is JobNode -> receiver.projectId != null && jobAction(receiver) == CiAction.EXECUTABLE
    is BridgeNode -> receiver.projectId != null && bridgeAction(receiver) == CiAction.EXECUTABLE
    else -> false
  }

  private fun jobAction(node: JobNode): CiAction? =
    CiStatus.contextAction(node.job.status, node.job.allowFailure ?: false)

  private fun bridgeAction(node: BridgeNode): CiAction? =
    CiStatus.contextAction(node.bridge.status, node.bridge.allowFailure ?: false)
}
