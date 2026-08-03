package com.gitlab.eclipse.mergerequests.discussions.actions

import com.gitlab.eclipse.api.DiscussionWriteService
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.NoteChangedException
import com.gitlab.eclipse.ci.actions.selectedSidebarNode
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.mergerequests.discussions.DiscussionGenerationRegistry
import com.gitlab.eclipse.mergerequests.discussions.DiscussionWriteKey
import com.gitlab.eclipse.mergerequests.discussions.DiscussionWriteOutcome
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.sidebar.NoteNode
import com.gitlab.eclipse.views.sidebar.canEditNote
import kotlinx.coroutines.CoroutineScope
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.handlers.HandlerUtil
import java.util.concurrent.atomic.AtomicReference

/** Window title, prompt, and OK label of the edit dialog. */
private const val EDIT_TITLE = "Edit comment"
private const val EDIT_DIALOG_PROMPT = "Edit this comment:"
private const val SAVE_LABEL = "Save"

/**
 * Replaces the generic `[Retry]` text when the edit was refused because the note changed in GitLab
 * after the sidebar loaded it. Retrying blindly would overwrite whatever someone else wrote, so the
 * message tells the user to refresh first.
 */
internal const val NOTE_CHANGED_MESSAGE =
  "This comment changed in GitLab after it was loaded. Refresh the discussions and edit it again."

/**
 * The error text for the edit flow's `[Retry]` dialog: the note-changed explanation when that is
 * precisely why the write failed, otherwise the launcher's own message. Pure and top-level so the
 * branch is unit-testable — the handler itself cannot be exercised headless.
 */
internal fun editRetryMessage(outcome: DiscussionWriteOutcome?, fallback: String): String =
  if (outcome is DiscussionWriteOutcome.Definite && outcome.cause is NoteChangedException) {
    NOTE_CHANGED_MESSAGE
  } else {
    fallback
  }

/**
 * Edits the body of the selected [NoteNode] (design §8.5, §13).
 *
 * The load-bearing part is the mutation: `assertNoteUnchanged` runs **inside the same `mutate`
 * lambda** and **strictly before** `updateNote`, so the pre-check is itself covered by the
 * connection gate and the pre-send lifecycle validation, and a [NoteChangedException] propagates
 * out of `mutate` to be classified `Definite` — which is what gives the user a `[Retry]` with
 * their typed text preserved. Its expected body is `node.body`, the text that was *displayed*
 * when the user chose to edit; comparing against the edited text would compare the new value with
 * itself and defeat the check entirely.
 *
 * The check narrows but cannot close the TOCTOU window — GitLab's `updateNote` has no optimistic
 * locking — which is why the retry message tells the user to refresh rather than just try again.
 */
@Suppress("unused")
class EditNoteHandler : AbstractHandler() {
  private val logger = logger<EditNoteHandler>()
  private val apiClient by lazyService<GitLabApiClient>()
  private val coroutineScope by lazyService<CoroutineScope>()
  private val writeService by lazyService<DiscussionWriteService>()

  override fun execute(event: ExecutionEvent): Any? {
    val node = selectedSidebarNode<NoteNode>(event)
    if (node == null) {
      NotificationUtils.show(SELECT_NOTE_MESSAGE)
      return null
    }
    if (!canEditNote(node)) {
      NotificationUtils.show(NO_PERMISSION_MESSAGE)
      return null
    }
    val window = HandlerUtil.getActiveWorkbenchWindow(event)
    val target = writeTargetOf(node)
    val key = DiscussionWriteKey.forNote(node.sourceInstanceUrl, node.sourceAuthFingerprint, node.noteGid)
    // Written on the background thread that ran the write, read on the UI thread that opens the
    // retry dialog — hence an AtomicReference rather than a plain field.
    val lastOutcome = AtomicReference<DiscussionWriteOutcome?>()
    // Cancelling the edit dialog leaves `body` holding the ORIGINAL note text; promptForBody's
    // Window.OK gate is what stops that from being re-saved as if the user had confirmed it.
    promptForBody(window, EDIT_TITLE, EDIT_DIALOG_PROMPT, node.body, null, SAVE_LABEL) { body ->
      val startEpoch = DiscussionGenerationRegistry.currentEpoch
      val launcher = discussionWriteLauncher(
        coroutineScope,
        logger,
        window,
        target,
        EDIT_TITLE,
        retryErrorMessage = { message -> editRetryMessage(lastOutcome.get(), message) },
      )
      launcher.launch(key, body, startEpoch) { attemptBody, attemptEpoch ->
        // attemptEpoch, not the captured startEpoch: a [Retry] re-entry re-freezes it, and sending
        // with the stale one would abort the confirmed edit with no UI at all.
        auditedDiscussionWrite(apiClient, logger, "editNote", target, key, attemptEpoch) { connection ->
          // Pre-check first, in the same lambda, against the DISPLAYED body.
          writeService.assertNoteUnchanged(connection, node.projectId, node.mrIid, node.noteGid, node.body)
          writeService.updateNote(connection, node.noteGid, attemptBody)
        }.also(lastOutcome::set)
      }
    }
    return null
  }
}
