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
 * UI thread only. Force-reloads **every** discussions section the write targeted, resolving them
 * from the tree **now** rather than capturing them when the command was invoked: the sidebar may
 * have been refreshed while the write was in flight, and a captured node would refresh something no
 * longer on screen.
 *
 * All matching sections are reloaded because one merge request can be displayed twice (under "Merge
 * requests assigned to me" and under "For current branch"), and there is no reliable way to tell
 * which of the two the user acted from — `DiscussionsSectionNode.nodeId` is per instance and a
 * refresh replaces the nodes. Reloading all of them guarantees the one the user is looking at is
 * among them.
 *
 * When the window, the view, or the section list is gone, [onOutcome] is deliberately **not**
 * invoked. The launcher already reads a never-invoked reload callback as "the current state was not
 * shown to the user", which is exactly right here — it then only preserves the typed text instead
 * of offering a `[Send again]` that could duplicate a comment.
 */
private fun reloadDiscussionsFor(
  window: IWorkbenchWindow?,
  target: DiscussionWriteTarget,
  onOutcome: (LoadOutcome) -> Unit,
) {
  val view = findSidebarViewIn(window) ?: return
  val sections = view.resolveDiscussionsSections(
    target.instanceUrl,
    target.authFingerprint,
    target.projectId,
    target.mrIid,
  )
  reloadAllSections(sections, { section, report -> view.reloadDiscussions(section, report) }, onOutcome)
}

/**
 * Starts [reload] for every section and reports a single aggregated [LoadOutcome] to [onOutcome]
 * once **all** of them have reported. Reports nothing at all for an empty [sections] — the caller's
 * contract is that a never-invoked callback means "the user was not shown the current state".
 *
 * Everything here runs on the UI thread: the launcher calls the reload from inside its `runOnUi`
 * block and [com.gitlab.eclipse.mergerequests.discussions.DiscussionsLoader] delivers its outcomes
 * on the UI thread too. The counter and the result slots are therefore plain, non-atomic fields on
 * purpose — **do not** add locks, atomics or `@Volatile`; there is no second thread to guard
 * against, and adding one would only hide that fact.
 *
 * If a section's callback is never invoked (shutdown), [onOutcome] is never invoked either — the
 * same, deliberate behaviour as the single-section path it replaces. A section that reports twice
 * is ignored the second time, so [onOutcome] can never fire twice.
 */
internal fun <S> reloadAllSections(
  sections: List<S>,
  reload: (S, (LoadOutcome) -> Unit) -> Unit,
  onOutcome: (LoadOutcome) -> Unit,
) {
  if (sections.isEmpty()) return
  val collected = arrayOfNulls<LoadOutcome>(sections.size)
  var pending = sections.size
  sections.forEachIndexed { index, section ->
    reload(section) { outcome ->
      if (collected[index] == null) {
        collected[index] = outcome
        pending--
        if (pending == 0) onOutcome(aggregateReloadOutcomes(collected.filterNotNull()))
      }
    }
  }
}

/**
 * The outcome to report for a set of per-section reloads: [LoadOutcome.Applied] only when **every**
 * section applied, otherwise the first non-`Applied` outcome in order.
 *
 * `Applied` is the only outcome that unlocks the launcher's `[Send again]` prompt, so it must mean
 * *every* place the user could be looking at now shows the server's state. A mixed list must fall
 * through to the copy-text dead end instead: telling a user their thread was reloaded when one of
 * its two displays is still stale is exactly what makes them post a duplicate comment.
 *
 * Total by construction, including the empty list — which maps to [LoadOutcome.Skipped], never
 * `Applied`, because "nothing was reloaded" must not unlock a re-send. That case is unreachable
 * from [reloadAllSections], which returns without reporting for an empty section list.
 */
internal fun aggregateReloadOutcomes(outcomes: List<LoadOutcome>): LoadOutcome {
  if (outcomes.isEmpty()) return LoadOutcome.Skipped
  return outcomes.firstOrNull { it != LoadOutcome.Applied } ?: LoadOutcome.Applied
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
