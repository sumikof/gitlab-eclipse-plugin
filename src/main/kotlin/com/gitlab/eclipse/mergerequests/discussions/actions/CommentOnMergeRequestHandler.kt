package com.gitlab.eclipse.mergerequests.discussions.actions

import com.gitlab.eclipse.api.DiscussionWriteService
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.ci.actions.selectedSidebarNode
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.mergerequests.discussions.DiscussionGenerationRegistry
import com.gitlab.eclipse.mergerequests.discussions.DiscussionWriteKey
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.sidebar.DiscussionsSectionNode
import com.gitlab.eclipse.views.sidebar.canCommentOnMergeRequest
import kotlinx.coroutines.CoroutineScope
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.jface.dialogs.IDialogConstants
import org.eclipse.ui.handlers.HandlerUtil

/** Window title and prompt of the merge-request comment dialog. */
private const val COMMENT_TITLE = "Comment on merge request"
private const val COMMENT_DIALOG_PROMPT = "Add a comment to this merge request:"

/**
 * Starts a new, non-line-anchored discussion on the merge request behind the selected
 * [DiscussionsSectionNode] (design §8.5). Identical shape to [ReplyToThreadHandler]; the only
 * differences are the node type, the `canCommentOnMergeRequest` permission, and the `replyId =
 * null` that turns the create into a new thread rather than a reply.
 *
 * The write key is [DiscussionWriteKey.forMergeRequest] because no discussion exists yet at send
 * time — the MR GID is the only identifier that predates the request, and it is what serializes
 * two rapid comments on the same merge request.
 */
@Suppress("unused")
class CommentOnMergeRequestHandler : AbstractHandler() {
  private val logger = logger<CommentOnMergeRequestHandler>()
  private val apiClient by lazyService<GitLabApiClient>()
  private val coroutineScope by lazyService<CoroutineScope>()
  private val writeService by lazyService<DiscussionWriteService>()

  override fun execute(event: ExecutionEvent): Any? {
    val node = selectedSidebarNode<DiscussionsSectionNode>(event)
    if (node == null) {
      NotificationUtils.show(SELECT_SECTION_MESSAGE)
      return null
    }
    if (!canCommentOnMergeRequest(node)) {
      NotificationUtils.show(NO_PERMISSION_MESSAGE)
      return null
    }
    val window = HandlerUtil.getActiveWorkbenchWindow(event)
    val target = writeTargetOf(node)
    val key = DiscussionWriteKey.forMergeRequest(node.sourceInstanceUrl, node.sourceAuthFingerprint, node.mrGid)
    promptForBody(window, COMMENT_TITLE, COMMENT_DIALOG_PROMPT, "", null, IDialogConstants.OK_LABEL) { body ->
      val startEpoch = DiscussionGenerationRegistry.currentEpoch
      discussionWriteLauncher(coroutineScope, logger, window, target, COMMENT_TITLE)
        .launch(key, body, startEpoch) { attemptBody ->
          auditedDiscussionWrite(apiClient, logger, "commentOnMergeRequest", target, key, startEpoch) { connection ->
            writeService.createNote(
              connection,
              node.mrGid,
              attemptBody,
              replyId = null,
              mergeRequestDiffHeadSha = node.mrSha,
            )
          }
        }
    }
    return null
  }
}
