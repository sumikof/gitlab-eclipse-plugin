package com.gitlab.eclipse.mergerequests.discussions.actions

import com.gitlab.eclipse.api.DiscussionWriteService
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.ci.actions.selectedSidebarNode
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.mergerequests.discussions.DiscussionGenerationRegistry
import com.gitlab.eclipse.mergerequests.discussions.DiscussionWriteKey
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.sidebar.NoteNode
import com.gitlab.eclipse.views.sidebar.canDeleteNote
import kotlinx.coroutines.CoroutineScope
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.jface.dialogs.MessageDialog
import org.eclipse.ui.handlers.HandlerUtil

/** Title and body of the delete confirmation. */
private const val DELETE_TITLE = "Delete comment"
private const val DELETE_CONFIRM_MESSAGE = "Delete this comment? This cannot be undone."

/**
 * Deletes the selected [NoteNode] (design §8.5). Destructive and irreversible, so it asks for
 * confirmation first; declining ends the handler before any key is acquired and before anything is
 * sent.
 *
 * The body is `""` — there is no typed text to preserve, so the launcher reports failures as plain
 * notifications rather than text-preserving dialogs.
 */
@Suppress("unused")
class DeleteNoteHandler : AbstractHandler() {
  private val logger = logger<DeleteNoteHandler>()
  private val apiClient by lazyService<GitLabApiClient>()
  private val coroutineScope by lazyService<CoroutineScope>()
  private val writeService by lazyService<DiscussionWriteService>()

  override fun execute(event: ExecutionEvent): Any? {
    val node = selectedSidebarNode<NoteNode>(event)
    if (node == null) {
      NotificationUtils.show(SELECT_NOTE_MESSAGE)
      return null
    }
    if (!canDeleteNote(node)) {
      NotificationUtils.show(NO_PERMISSION_MESSAGE)
      return null
    }
    val window = HandlerUtil.getActiveWorkbenchWindow(event)
    if (!MessageDialog.openConfirm(window?.shell, DELETE_TITLE, DELETE_CONFIRM_MESSAGE)) return null
    val target = writeTargetOf(node)
    val key = DiscussionWriteKey.forNote(node.sourceInstanceUrl, node.sourceAuthFingerprint, node.noteGid)
    val startEpoch = DiscussionGenerationRegistry.currentEpoch
    discussionWriteLauncher(coroutineScope, logger, window, target, DELETE_TITLE)
      .launch(key, "", startEpoch) { _, attemptEpoch ->
        // attemptEpoch, not the captured startEpoch: a [Retry] re-entry re-freezes it, and sending
        // with the stale one would abort the retry with no UI at all.
        auditedDiscussionWrite(apiClient, logger, "deleteNote", target, key, attemptEpoch) { connection ->
          writeService.destroyNote(connection, node.noteGid)
        }
      }
    return null
  }
}
