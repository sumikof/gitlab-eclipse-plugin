package com.gitlab.eclipse.mergerequests.discussions.actions

import com.gitlab.eclipse.api.DiscussionWriteService
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.ci.actions.selectedSidebarNode
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.mergerequests.discussions.DiscussionGenerationRegistry
import com.gitlab.eclipse.mergerequests.discussions.DiscussionWriteKey
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.sidebar.ThreadNode
import com.gitlab.eclipse.views.sidebar.canReplyToThread
import kotlinx.coroutines.CoroutineScope
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.jface.dialogs.IDialogConstants
import org.eclipse.ui.handlers.HandlerUtil

/** Window title and prompt of the reply dialog. */
private const val REPLY_TITLE = "Reply"
private const val REPLY_DIALOG_PROMPT = "Reply to this discussion thread:"

/**
 * Adds a note to the selected [ThreadNode] (design §8.5). Thin SWT shell: it captures the node
 * and the workbench window on the UI thread, re-checks the very same
 * [canReplyToThread][com.gitlab.eclipse.views.sidebar.canReplyToThread] predicate the context
 * menu's `visibleWhen` uses, collects the text, freezes the generation epoch in that same UI turn,
 * and hands everything to [DiscussionWriteLauncher][com.gitlab.eclipse.mergerequests.discussions.DiscussionWriteLauncher],
 * which owns the in-flight guard, the reload, and every failure dialog.
 */
@Suppress("unused")
class ReplyToThreadHandler : AbstractHandler() {
  private val logger = logger<ReplyToThreadHandler>()
  private val apiClient by lazyService<GitLabApiClient>()
  private val coroutineScope by lazyService<CoroutineScope>()
  private val writeService by lazyService<DiscussionWriteService>()

  override fun execute(event: ExecutionEvent): Any? {
    val node = selectedSidebarNode<ThreadNode>(event)
    if (node == null) {
      NotificationUtils.show(SELECT_THREAD_MESSAGE)
      return null
    }
    // The identical predicate the menu evaluated — never an inline copy of the condition, which
    // is how the menu and the handler drift apart.
    if (!canReplyToThread(node)) {
      NotificationUtils.show(NO_PERMISSION_MESSAGE)
      return null
    }
    val window = HandlerUtil.getActiveWorkbenchWindow(event)
    val target = writeTargetOf(node)
    val key = DiscussionWriteKey.forDiscussion(node.sourceInstanceUrl, node.sourceAuthFingerprint, node.replyId)
    // Cancel / ESC / close never reaches the lambda, so nothing is sent and no guard is taken.
    promptForBody(window, REPLY_TITLE, REPLY_DIALOG_PROMPT, "", null, IDialogConstants.OK_LABEL) { body ->
      // Frozen after the dialog closes but still on this UI thread, in the same turn as launch().
      val startEpoch = DiscussionGenerationRegistry.currentEpoch
      discussionWriteLauncher(coroutineScope, logger, window, target, REPLY_TITLE)
        .launch(key, body, startEpoch) { attemptBody ->
          // attemptBody, not body: a [Retry] re-entry may carry edited text.
          auditedDiscussionWrite(apiClient, logger, "replyToThread", target, key, startEpoch) { connection ->
            writeService.createNote(
              connection,
              node.mrGid,
              attemptBody,
              replyId = node.replyId,
              mergeRequestDiffHeadSha = node.mrSha,
            )
          }
        }
    }
    return null
  }
}
