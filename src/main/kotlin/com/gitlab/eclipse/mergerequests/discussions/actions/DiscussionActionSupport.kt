package com.gitlab.eclipse.mergerequests.discussions.actions

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.ci.actions.findSidebarViewIn
import com.gitlab.eclipse.mergerequests.discussions.CommentInputDialog
import com.gitlab.eclipse.mergerequests.discussions.DiscussionWriteKey
import com.gitlab.eclipse.mergerequests.discussions.DiscussionWriteLauncher
import com.gitlab.eclipse.mergerequests.discussions.DiscussionWriteOutcome
import com.gitlab.eclipse.mergerequests.discussions.LoadOutcome
import com.gitlab.eclipse.mergerequests.discussions.discussionAuditMessage
import com.gitlab.eclipse.mergerequests.discussions.runDiscussionWrite
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.views.sidebar.DiscussionsSectionNode
import com.gitlab.eclipse.views.sidebar.NoteNode
import com.gitlab.eclipse.views.sidebar.ThreadNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.core.runtime.ILog
import org.eclipse.jface.dialogs.IDialogConstants
import org.eclipse.jface.window.Window
import org.eclipse.ui.IWorkbenchWindow

/** Shown when the command fires with no usable node selected — one message per node kind. */
internal const val SELECT_THREAD_MESSAGE = "Select a discussion thread in the GitLab sidebar first."
internal const val SELECT_NOTE_MESSAGE = "Select a comment in the GitLab sidebar first."
internal const val SELECT_SECTION_MESSAGE = "Select a merge request's Discussions section in the GitLab sidebar first."

/**
 * Shown when the handler's runtime permission re-check fails. The menu should already have hidden
 * the action, so reaching this means the tree data is stale (the permission changed server-side,
 * or the thread was resolved elsewhere since the sidebar loaded).
 */
internal const val NO_PERMISSION_MESSAGE =
  "Your GitLab account cannot do that on this item. Refresh the sidebar and try again."

/** Prompts shown above the text area of each flavour of [CommentInputDialog]. */
internal const val RETRY_PROMPT = "Your text was kept. Edit it if you want, then try again."
internal const val SEND_AGAIN_PROMPT = "Your text was kept. Send it again only if it is not already shown above."
internal const val COPY_TEXT_PROMPT = "Your text was not sent. Copy it from here if you want to keep it."

/** OK-button labels; the launcher's two re-send prompts are distinguished only by this label. */
internal const val RETRY_LABEL = "Retry"
internal const val SEND_AGAIN_LABEL = "Send again"

/**
 * The connection tags and merge-request identifiers every discussion write needs, lifted off the
 * selected node **on the UI thread** at invocation time. Taking them from the node rather than
 * re-capturing them later is what makes the connection gate meaningful: the write is checked
 * against the instance and account the displayed data was actually fetched over, so a settings
 * change between the click and the send is caught instead of silently re-targeted.
 */
internal data class DiscussionWriteTarget(
  val instanceUrl: String,
  val authFingerprint: String,
  val projectId: Long,
  val mrIid: Long,
)

internal fun writeTargetOf(node: ThreadNode) =
  DiscussionWriteTarget(node.sourceInstanceUrl, node.sourceAuthFingerprint, node.projectId, node.mrIid)

internal fun writeTargetOf(node: NoteNode) =
  DiscussionWriteTarget(node.sourceInstanceUrl, node.sourceAuthFingerprint, node.projectId, node.mrIid)

internal fun writeTargetOf(node: DiscussionsSectionNode) =
  DiscussionWriteTarget(node.sourceInstanceUrl, node.sourceAuthFingerprint, node.projectId, node.mrIid)

/**
 * Builds the [DiscussionWriteLauncher] for one invocation, binding its eight injected effects to
 * the real workbench. Shared by all five handlers on purpose: these bindings carry the anti-
 * duplicate-post and text-preservation guarantees, and five hand-rolled copies would be five
 * chances to get one of them subtly wrong.
 *
 * [retryErrorMessage] maps the launcher's own `[Retry]` message to the text actually rendered in
 * the dialog; the default is the identity, and only [EditNoteHandler] overrides it (to explain a
 * concurrent edit). [dialogTitle] is reused for every prompt of the flow so the user sees one
 * consistent window title.
 */
internal fun discussionWriteLauncher(
  scope: CoroutineScope,
  log: ILog,
  window: IWorkbenchWindow?,
  target: DiscussionWriteTarget,
  dialogTitle: String,
  retryErrorMessage: (String) -> String = { it },
): DiscussionWriteLauncher = DiscussionWriteLauncher(
  runInBackground = { block -> scope.launch { block() } },
  runOnUi = { block -> currentDisplay.asyncExec { block() } },
  reload = { onOutcome -> reloadDiscussionsFor(window, target, onOutcome) },
  notify = { message -> NotificationUtils.show(message) },
  promptRetry = { message, body, onRetry ->
    promptForBody(window, dialogTitle, RETRY_PROMPT, body, retryErrorMessage(message), RETRY_LABEL, onRetry)
  },
  promptSendAgain = { message, body, onSendAgain ->
    promptForBody(window, dialogTitle, SEND_AGAIN_PROMPT, body, message, SEND_AGAIN_LABEL, onSendAgain)
  },
  promptCopyText = { message, body -> showCopyTextDialog(window, dialogTitle, message, body) },
  log = { message -> log.info(message) },
)

/**
 * UI thread only. Force-reloads the discussions section the write targeted, resolving it from the
 * tree **now** rather than capturing it when the command was invoked: the sidebar may have been
 * refreshed while the write was in flight, and a captured node would refresh something no longer
 * on screen.
 *
 * When the window, the view, or the section is gone, [onOutcome] is deliberately **not** invoked.
 * The launcher already reads a never-invoked reload callback as "the current state was not shown
 * to the user", which is exactly right here — it then only preserves the typed text instead of
 * offering a `[Send again]` that could duplicate a comment.
 */
private fun reloadDiscussionsFor(
  window: IWorkbenchWindow?,
  target: DiscussionWriteTarget,
  onOutcome: (LoadOutcome) -> Unit,
) {
  val view = findSidebarViewIn(window) ?: return
  val section = view.resolveDiscussionsSection(
    target.instanceUrl,
    target.authFingerprint,
    target.projectId,
    target.mrIid,
  ) ?: return
  view.reloadDiscussions(section, onOutcome)
}

/**
 * UI thread only. Opens a [CommentInputDialog] and invokes [onAccepted] with the entered text
 * **only when the dialog was confirmed**.
 *
 * The return-code check is load-bearing, not defensive: [CommentInputDialog.body] starts out as
 * `initialBody` and is overwritten only in `okPressed`, so Cancel, ESC, and the window's close
 * button all leave a *non-empty* body behind on an edit or a retry. Reading `body` without the
 * `Window.OK` gate would re-send a comment the user just decided to abandon.
 */
internal fun promptForBody(
  window: IWorkbenchWindow?,
  title: String,
  prompt: String,
  initialBody: String,
  errorMessage: String?,
  okLabel: String,
  onAccepted: (String) -> Unit,
) {
  val dialog = CommentInputDialog(window?.shell, title, prompt, initialBody, errorMessage, okLabel)
  if (dialog.open() == Window.OK) onAccepted(dialog.body)
}

/**
 * UI thread only. Text-preserving dead end: the user can select and copy what they typed and
 * nothing else. The result is discarded on purpose — there is no callback and therefore no way to
 * re-send from here, which is the whole point (the write's outcome was unconfirmed, so a re-send
 * could duplicate it).
 */
internal fun showCopyTextDialog(window: IWorkbenchWindow?, title: String, message: String, body: String) {
  CommentInputDialog(window?.shell, title, COPY_TEXT_PROMPT, body, message, IDialogConstants.CLOSE_LABEL).open()
}

/**
 * Background thread. Runs one write through the connection gate + pre-send lifecycle re-check and
 * emits exactly one secret-free audit line for its outcome (design §16). [key] supplies only its
 * `targetKind`; the target id is deliberately never logged.
 */
internal fun auditedDiscussionWrite(
  apiClient: GitLabApiClient,
  log: ILog,
  action: String,
  target: DiscussionWriteTarget,
  key: DiscussionWriteKey,
  startEpoch: Long,
  mutate: (ConnectionSnapshot) -> Unit,
): DiscussionWriteOutcome {
  val outcome = runDiscussionWrite(
    apiClient,
    target.instanceUrl,
    target.authFingerprint,
    startEpoch,
    mutate = mutate,
  )
  log.info(
    discussionAuditMessage(action, target.instanceUrl, target.projectId, target.mrIid, key.targetKind, outcome),
  )
  return outcome
}
