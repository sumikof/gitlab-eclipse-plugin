package com.gitlab.eclipse.mergerequests.review

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.DiscussionWriteService
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.mergerequests.discussions.DiscussionWriteKey
import com.gitlab.eclipse.mergerequests.discussions.DiscussionWriteLauncher
import com.gitlab.eclipse.mergerequests.discussions.DiscussionWriteOutcome
import com.gitlab.eclipse.mergerequests.discussions.LoadOutcome
import com.gitlab.eclipse.mergerequests.discussions.actions.DiscussionWriteTarget
import com.gitlab.eclipse.mergerequests.discussions.actions.auditedDiscussionWrite
import com.gitlab.eclipse.views.inlinethread.InlineThreadAction
import com.gitlab.eclipse.views.inlinethread.InlineThreadHost
import com.gitlab.eclipse.views.inlinethread.InlineThreadSurface
import com.gitlab.eclipse.views.inlinethread.NEW_THREAD_ID
import com.gitlab.eclipse.views.inlinethread.SubmitTicket
import com.gitlab.eclipse.views.inlinethread.SuccessEffect
import kotlinx.coroutines.CancellationException
import org.eclipse.core.runtime.ILog
import java.util.concurrent.ConcurrentHashMap

/** What an MR thread popup is about: a new thread on a frozen line, or the threads of a loaded session's line. */
sealed interface MrPopupKind {
  /** The "new thread" popup; [snapshot] is what UI turn 1 froze (design §9.3). */
  data class NewThread(val snapshot: LineSnapshot) : MrPopupKind

  /** A popup over existing threads; [session] is the snapshot the popup was opened from (its identity and MR ref never change). */
  data class ExistingThreads(val session: ReviewSessionSnapshot) : MrPopupKind
}

/**
 * The three background writes a popup can issue. Each is one launcher `write`: called on the
 * background thread with the body and epoch of the attempt that is running, returning the
 * classified outcome, never throwing for a send failure.
 */
interface MrThreadWrites {
  fun create(
    snapshot: LineSnapshot,
    session: ReviewSessionSnapshot?,
    target: AttemptTarget,
    body: String,
    startEpoch: Long,
  ): DiscussionWriteOutcome

  fun reply(session: ReviewSessionSnapshot, replyId: String, body: String, startEpoch: Long): DiscussionWriteOutcome

  fun resolve(session: ReviewSessionSnapshot, replyId: String, resolved: Boolean, startEpoch: Long): DiscussionWriteOutcome
}

/** The production [MrThreadWrites]: the line-comment attempt, and the same audited `createNote` / `toggleResolve` the sidebar handlers use. */
class GitLabMrThreadWrites(
  private val apiClient: GitLabApiClient,
  private val writeService: DiscussionWriteService,
  private val attempt: LineCommentAttempt,
  private val log: ILog,
) : MrThreadWrites {
  override fun create(
    snapshot: LineSnapshot,
    session: ReviewSessionSnapshot?,
    target: AttemptTarget,
    body: String,
    startEpoch: Long,
  ): DiscussionWriteOutcome = attempt.run(snapshot, session, target, body, startEpoch)

  override fun reply(session: ReviewSessionSnapshot, replyId: String, body: String, startEpoch: Long): DiscussionWriteOutcome =
    audited(session, "replyToThread", replyId, startEpoch) { connection ->
      writeService.createNote(
        connection,
        session.mrRef.mrGid,
        body,
        replyId = replyId,
        mergeRequestDiffHeadSha = session.headSha,
      )
    }

  override fun resolve(session: ReviewSessionSnapshot, replyId: String, resolved: Boolean, startEpoch: Long): DiscussionWriteOutcome =
    audited(session, if (resolved) "resolveThread" else "unresolveThread", replyId, startEpoch) { connection ->
      // `resolved` is a target state, never a flip of the current value.
      writeService.toggleResolve(connection, replyId, resolved = resolved)
    }

  private fun audited(
    session: ReviewSessionSnapshot,
    action: String,
    replyId: String,
    startEpoch: Long,
    mutate: (ConnectionSnapshot) -> Unit,
  ): DiscussionWriteOutcome {
    val identity = session.identity
    val target = with(identity) { DiscussionWriteTarget(instanceUrl, authFingerprint, projectId, mrIid) }
    val key = DiscussionWriteKey.forDiscussion(identity.instanceUrl, identity.authFingerprint, replyId)
    return auditedDiscussionWrite(apiClient, log, action, target, key, startEpoch, mutate)
  }
}

/**
 * The MR implementation of [InlineThreadHost] (design §9.2, §9.3.1, §9.4, §29 #20–#22): turns
 * the popup's submit and resolve requests into [DiscussionWriteLauncher] launches, wraps every
 * `write` so that busy is released on every terminal and the success effect is applied only for
 * a Success, and supplies the **editor-path `reload`**, which establishes or refreshes the review
 * session and asks the sidebar to reload but always reports [LoadOutcome.Skipped] — so an
 * Ambiguous write on this path never claims the thread was reloaded (A20), and its thread cannot be
 * submitted again from the same popup (#96).
 *
 * SWT-free: thread hops and every effect are injected. [runOnUi] must schedule onto the UI thread
 * without blocking (`asyncExec`); a scheduling failure is swallowed with one log line, as in
 * `DiscussionWriteLauncher.scheduleTerminal`, so the shared background scope is never cancelled.
 * All members are called on the UI thread; only the launcher's `write` (built here) runs in the
 * background, and it touches the popup's state exclusively through [runOnUi].
 *
 * A new [DiscussionWriteLauncher] is built per launch through [newLauncher], because the reload
 * of a new-thread launch reads that launch's own [AttemptTarget].
 *
 * @param sessionNow the session snapshot of the popup's editor, read on the UI thread when a
 *   new-thread submit is launched (`ReviewSessionRegistry.snapshotFor`).
 * @param refreshSession refreshes the session for the identity, or establishes it when the
 *   document has none (`ReviewSessionRegistry.refresh` → `begin`).
 * @param reloadSidebar the existing `reloadDiscussionsFor` for the identity's MR (its outcome is not awaited).
 * @param preserveDraft the copy-text dialog for one unsent draft when the popup closes (§29 #22).
 * @param newThreadStale UI thread, at a new-thread submit before the launch (Codex r4): whether
 *   the popup's editor no longer holds the frozen snapshot (dirty, or its text differs), read off
 *   the live editor ([newThreadSnapshotStale]). The popup is non-modal, so the editor may have been
 *   edited since UI turn 1; the send-time body-identity gate compares only the file on disk.
 * @param notify the fixed notification of a refused launch.
 * @param newThreadEdited background, at the start of **every** new-thread attempt — the first
 *   send and each `[Retry]` re-entry, which never passes through [onSubmit] (Codex r5): whether
 *   the popup's editor document changed since the popup opened, or the popup is closed
 *   ([NewThreadEditTracker.edited], fail closed). `true` refuses the attempt with
 *   [FILE_CHANGED_MESSAGE] before anything is sent.
 */
class MrThreadPopupHost(
  private val kind: MrPopupKind,
  private val sessionNow: () -> ReviewSessionSnapshot?,
  private val writes: MrThreadWrites,
  private val newLauncher: (reload: (onOutcome: (LoadOutcome) -> Unit) -> Unit) -> DiscussionWriteLauncher,
  private val runOnUi: (() -> Unit) -> Unit,
  private val currentEpoch: () -> Long,
  private val refreshSession: (SessionIdentity, MergeRequestRef) -> Unit,
  private val reloadSidebar: (SessionIdentity) -> Unit,
  private val preserveDraft: (String) -> Unit,
  private val log: (String) -> Unit,
  private val onPopupClosed: () -> Unit = {},
  private val newThreadStale: (LineSnapshot) -> Boolean = { false },
  private val notify: (String) -> Unit = {},
  private val newThreadEdited: () -> Boolean = { false },
) : InlineThreadHost {

  /**
   * Threads of this popup whose attempt ended Ambiguous (#96): they may already be posted, so any
   * later attempt for them is refused in [wrapped] before sending. Written and read on background
   * threads (the launcher's `write`), hence concurrent; never cleared — a deliberate re-send takes
   * a new popup, which gets a new host.
   */
  private val unconfirmedThreads: MutableSet<String> = ConcurrentHashMap.newKeySet()

  /** UI thread. [ticket] was frozen by the popup's `beginSubmit`; busy is released on every path from here. */
  override fun onSubmit(surface: InlineThreadSurface, ticket: SubmitTicket) {
    if (ticket.threadId == NEW_THREAD_ID && kind is MrPopupKind.NewThread && newThreadStale(kind.snapshot)) {
      // Codex r4: the frozen line no longer matches what the user sees; nothing is launched and the draft stays.
      log("threadPopup submit refused: the editor changed after the popup opened.")
      notify(FILE_CHANGED_MESSAGE)
      surface.state.onLaunchRejected(ticket)
      surface.refresh()
      return
    }
    val launch = launchFor(ticket)
    if (launch == null) {
      log("threadPopup submit refused: ticket does not match the popup kind.")
      surface.state.onLaunchRejected(ticket)
      surface.refresh()
      return
    }
    val launcher = newLauncher { onOutcome -> editorPathReload(launch.target(), onOutcome) }
    val accepted = launcher.launch(launch.key, ticket.body, currentEpoch()) { body, epoch ->
      wrapped(surface, ticket) { launch.write(body, epoch) }
    }
    // §29 #20: the guard was taken by another write for this key — release busy right away.
    if (!accepted) {
      surface.state.onLaunchRejected(ticket)
      surface.refresh()
    }
  }

  /** UI thread. RESOLVE / UNRESOLVE of the selected thread; no text, no busy (the launcher's key serializes the writes). */
  override fun onAction(surface: InlineThreadSurface, action: InlineThreadAction) {
    val resolved = when (action) {
      InlineThreadAction.RESOLVE -> true
      InlineThreadAction.UNRESOLVE -> false
      InlineThreadAction.REPLY, InlineThreadAction.CREATE -> return // these are submits, routed through onSubmit
    }
    val session = (kind as? MrPopupKind.ExistingThreads)?.session
    if (session == null) {
      log("threadPopup resolve refused: no existing thread.")
      return
    }
    val replyId = surface.state.selectedThreadId
    val launcher = newLauncher { onOutcome -> editorPathReload(session.identity to session.mrRef, onOutcome) }
    launcher.launch(keyFor(session, replyId), "", currentEpoch()) { _, epoch ->
      writes.resolve(session, replyId, resolved, epoch)
    }
  }

  override fun preserveDrafts(drafts: List<String>) {
    drafts.forEach(preserveDraft)
  }

  override fun onClosed() {
    onPopupClosed()
  }

  /** One launch's key, write and the MR target its reload reads. */
  private class Launch(
    val key: DiscussionWriteKey,
    val write: (String, Long) -> DiscussionWriteOutcome,
    val target: () -> Pair<SessionIdentity, MergeRequestRef>?,
  )

  private fun launchFor(ticket: SubmitTicket): Launch? = when {
    ticket.threadId == NEW_THREAD_ID && kind is MrPopupKind.NewThread -> {
      val snapshot = kind.snapshot
      // Captured on the UI thread now; a [Retry] re-entry reuses it, and the attempt re-checks its tags.
      val session = sessionNow()
      val holder = AttemptTarget()
      Launch(
        key = DiscussionWriteKey.forEditorLine(snapshot.filePath.path, snapshot.oneBasedLine),
        write = { body, epoch ->
          if (newThreadEdited()) {
            // Codex r5: also on a [Retry] re-entry — the editor moved while the earlier attempt ran.
            log("threadPopup create refused: the editor changed after the popup opened.")
            DiscussionWriteOutcome.Rejected(FILE_CHANGED_MESSAGE)
          } else {
            writes.create(snapshot, session, holder, body, epoch)
          }
        },
        target = { holder.value },
      )
    }
    ticket.threadId != NEW_THREAD_ID && kind is MrPopupKind.ExistingThreads -> {
      val session = kind.session
      Launch(
        key = keyFor(session, ticket.threadId),
        write = { body, epoch -> writes.reply(session, ticket.threadId, body, epoch) },
        target = { session.identity to session.mrRef },
      )
    }
    else -> null
  }

  /**
   * Background. Design §9.3.1 / §29 #20: whatever [attempt] returns or throws, the popup is told
   * the attempt finished (busy released); only a Success then applies the state's effect, and only
   * if the ticket's thread is unedited since (the state decides). An Ambiguous attempt releases busy
   * through `state.onAttemptUnconfirmed` in the same UI hop, which also locks the thread
   * against a re-send (#96): the kept draft must never sit behind an enabled Send button.
   */
  private fun wrapped(
    surface: InlineThreadSurface,
    ticket: SubmitTicket,
    attempt: () -> DiscussionWriteOutcome,
  ): DiscussionWriteOutcome {
    // Checked here, in the background attempt and therefore AFTER the launcher acquired the write
    // guard, never in onSubmit: an earlier Ambiguous attempt records its thread below before it
    // returns, i.e. before the launcher releases the guard, so a submit that won the guard after
    // that release is guaranteed to see the record — even when it was queued ahead of the UI lock
    // hop, or came from a `[Retry]` that never re-armed busy (Codex r3). Nothing is sent.
    if (ticket.threadId in unconfirmedThreads) {
      hop("attemptRefused") {
        surface.state.onAttemptUnconfirmed(ticket)
        surface.refresh()
      }
      return DiscussionWriteOutcome.Rejected(UNCONFIRMED_RESEND_MESSAGE)
    }
    var unconfirmed = false
    val outcome = try {
      attempt().also {
        if (it is DiscussionWriteOutcome.Ambiguous) {
          unconfirmed = true
          unconfirmedThreads += ticket.threadId
        }
      }
    } finally {
      // A throw is not Ambiguous here: the launcher classifies an escaped throwable as Definite
      // (nothing was transmitted), so only a returned Ambiguous locks the thread.
      val lock = unconfirmed
      hop("attemptFinished") {
        if (lock) surface.state.onAttemptUnconfirmed(ticket) else surface.state.onAttemptFinished(ticket)
        surface.refresh()
      }
    }
    if (outcome == DiscussionWriteOutcome.Success) {
      hop("succeeded") {
        when (surface.state.onSucceeded(ticket)) {
          SuccessEffect.NONE -> Unit
          // The popup re-syncs its input from the selected thread's draft; the cleared draft may
          // belong to another thread than the one on screen.
          SuccessEffect.CLEAR_DRAFT -> surface.refresh()
          SuccessEffect.CLOSE -> surface.close()
        }
      }
    }
    return outcome
  }

  /**
   * Schedules [block] on the UI thread, containing both a scheduling failure (disposed display,
   * torn-down workbench: the same blast radius `DiscussionWriteLauncher.scheduleTerminal` guards
   * against) and a failure inside the block (never an unhandled event loop exception). Class name
   * only in the log. Cancellation is rethrown.
   */
  private fun hop(step: String, block: () -> Unit) {
    try {
      runOnUi {
        try {
          block()
        } catch (e: Exception) {
          runCatching { log("threadPopup $step failed: exceptionType=${e.javaClass.simpleName}") }
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      runCatching { log("threadPopup $step uiSchedulingFailed exceptionType=${e.javaClass.simpleName}") }
    }
  }

  /**
   * UI thread, the launcher's terminal (Success / Ambiguous). Establishes or refreshes the session
   * of the attempt's MR target and asks the sidebar to reload, then reports [LoadOutcome.Skipped]
   * **whatever happened** (design §9.3.1, FR-10): the mutation may still be committing server-side
   * when a reload lands, so a reload can never prove "not posted".
   * A target of `null` (the attempt failed before G6) does neither.
   */
  private fun editorPathReload(target: Pair<SessionIdentity, MergeRequestRef>?, onOutcome: (LoadOutcome) -> Unit) {
    if (target == null) {
      log("threadPopup reload skipped: no MR target.")
    } else {
      guarded("session refresh") { refreshSession(target.first, target.second) }
      guarded("sidebar reload") { reloadSidebar(target.first) }
    }
    onOutcome(LoadOutcome.Skipped)
  }

  private inline fun guarded(step: String, block: () -> Unit) {
    try {
      block()
    } catch (e: Exception) {
      log("threadPopup $step failed: exceptionType=${e.javaClass.simpleName}")
    }
  }

  private fun keyFor(session: ReviewSessionSnapshot, replyId: String) =
    DiscussionWriteKey.forDiscussion(session.identity.instanceUrl, session.identity.authFingerprint, replyId)

  companion object {
    /** Fixed text, no path or body (design §19). */
    const val FILE_CHANGED_MESSAGE =
      "The file changed after you started this comment. Save or undo your changes, then start the comment again from the line."

    /** Fixed text (design §19): an earlier send of this thread from this popup could not be confirmed (#96). */
    const val UNCONFIRMED_RESEND_MESSAGE =
      "An earlier send from this popup could not be confirmed and may already be posted. " +
        "Check in GitLab, then close this popup and start again if it is missing."
  }
}
