package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.ci.CiAction
import com.gitlab.eclipse.ci.CiStatus
import org.eclipse.core.expressions.PropertyTester

/**
 * Status-dependent visibility for the sidebar pipeline/job context-menu actions (design §8.6).
 * A node with a null projectId can never act (write routing needs it), so every property is
 * false then.
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
    else -> false
  }

  private fun canCancel(receiver: Any?): Boolean = when (receiver) {
    is PipelineNode -> receiver.projectId != null && receiver.canCancel
    is JobNode -> receiver.projectId != null && jobAction(receiver) == CiAction.CANCELLABLE
    else -> false
  }

  private fun canPlay(receiver: Any?): Boolean =
    receiver is JobNode && receiver.projectId != null && jobAction(receiver) == CiAction.EXECUTABLE

  private fun jobAction(node: JobNode): CiAction? =
    CiStatus.contextAction(node.job.status, node.job.allowFailure ?: false)
}
