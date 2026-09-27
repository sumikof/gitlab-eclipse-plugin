package com.gitlab.eclipse.mergerequests.review

import com.gitlab.eclipse.api.DiscussionWriteService
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.mergerequests.discussions.DiscussionGenerationRegistry
import com.gitlab.eclipse.mergerequests.discussions.DiscussionWriteLauncher
import com.gitlab.eclipse.mergerequests.discussions.LoadOutcome
import com.gitlab.eclipse.mergerequests.discussions.actions.DiscussionWriteTarget
import com.gitlab.eclipse.mergerequests.discussions.actions.RETRY_LABEL
import com.gitlab.eclipse.mergerequests.discussions.actions.RETRY_PROMPT
import com.gitlab.eclipse.mergerequests.discussions.actions.SEND_AGAIN_LABEL
import com.gitlab.eclipse.mergerequests.discussions.actions.SEND_AGAIN_PROMPT
import com.gitlab.eclipse.mergerequests.discussions.actions.promptForBody
import com.gitlab.eclipse.mergerequests.discussions.actions.reloadDiscussionsFor
import com.gitlab.eclipse.mergerequests.discussions.actions.showCopyTextDialog
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.inlinethread.InlineThreadModel
import com.gitlab.eclipse.views.inlinethread.InlineThreadPopup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.jface.text.IDocument
import org.eclipse.swt.widgets.Control
import org.eclipse.ui.IEditorInput
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.texteditor.ITextEditor

/**
 * The MR thread popups of the workbench (design §8.2, §9.2, §9.4, §9.6, FR-5, FR-11): **one popup
 * per workbench window**, opened from the ruler click ([ReviewSessionRegistry.threadOpener]) or
 * the two editor commands, closed when its editor or session goes away, and refreshed when a
 * reload lands. **UI thread only.**
 *
 * [install] is called once at activation (`GitLabEclipseStartup.activateGenerationRegistries`,
 * in the same UI turn as `DiscussionGenerationRegistry.onActivate`) and sets the registry's hooks;
 * [discardAll] runs from the stop hook right before [ReviewSessionRegistry.clear], closing every
 * popup without a copy-text prompt (no dialogs at bundle stop, §29 #22's known limitation).
 *
 * Entry points for the command handlers (both UI thread, both no-ops once the bundle is inactive):
 * - [openThreads] for "Open Merge Request Thread" on an annotated line;
 * - [openNewThread] for "Add Merge Request Comment on This Line…" with the frozen [LineSnapshot].
 */
object MrThreadPopups {
  private val logger by lazy { logger<MrThreadPopups>() }
  private val coroutineScope by lazyService<CoroutineScope>()
  private val apiClient by lazyService<GitLabApiClient>()
  private val writeService by lazyService<DiscussionWriteService>()
  private val writes: MrThreadWrites by lazy {
    GitLabMrThreadWrites(apiClient, writeService, LineCommentAttempt(), logger)
  }

  /** [threadIds]: for an [MrPopupKind.ExistingThreads] popup, the reply ids it shows (re-mapped by id on a reload). */
  private class Open(
    val popup: InlineThreadPopup,
    val editor: ITextEditor,
    val document: IDocument?,
    val kind: MrPopupKind,
    var threadIds: List<String>,
  )

  private val open = HashMap<IWorkbenchWindow, Open>()

  /** UI thread, once per activation. Wires the registry hooks; the ruler click opens in a later `asyncExec` turn, never inside the mouse dispatch. */
  fun install() {
    ReviewSessionRegistry.threadOpener = { editor, oneBasedLine ->
      val display = currentDisplay
      display.asyncExec {
        if (!display.isDisposed) guardedUi("open from ruler") { openThreads(editor, oneBasedLine) }
      }
    }
    ReviewSessionRegistry.onEditorReleased = { editor, _ -> closeWhere { it.editor === editor } }
    ReviewSessionRegistry.onSessionReleased = { document -> closeWhere { it.document == document } }
    ReviewSessionRegistry.onSnapshotApplied = { document, snapshot -> applySnapshot(document, snapshot) }
  }

  /**
   * UI thread. Opens (or re-activates) the popup over the threads whose annotations currently sit
   * on [oneBasedLine] in [editor] (the clicked live line; annotations follow edits, E4), looked up
   * in the session snapshot by reply id — never the placements loaded on that line number, which
   * may be another thread once the text above moved. Nothing to show → one notification.
   */
  fun openThreads(editor: ITextEditor, oneBasedLine: Int) {
    if (!DiscussionGenerationRegistry.active) return
    val session = ReviewSessionRegistry.snapshotFor(editor)
    if (session == null) {
      NotificationUtils.showOnUiThread(NO_SESSION_MESSAGE)
      return
    }
    val threadIds = ReviewSessionRegistry.threadIdsAt(editor, oneBasedLine)
    val model = MrThreadModelMapper.threadsWithIds(session, threadIds)
    if (model == null) {
      NotificationUtils.showOnUiThread(NO_THREAD_MESSAGE)
      return
    }
    show(editor, oneBasedLine, MrPopupKind.ExistingThreads(session), model, model.items.map { it.threadId })
  }

  /**
   * UI thread. Opens the "new thread" popup for the frozen [snapshot] (design §9.3 UI turn 1). A
   * loaded session that forbids notes refuses right here (FR-9); without a session the attempt's
   * G6b decides at send time.
   */
  fun openNewThread(editor: ITextEditor, snapshot: LineSnapshot) {
    if (!DiscussionGenerationRegistry.active) return
    val model = MrThreadModelMapper.newThread(snapshot.oneBasedLine, ReviewSessionRegistry.snapshotFor(editor))
    if (model == null) {
      NotificationUtils.showOnUiThread(LineCommentAttempt.NO_PERMISSION_MESSAGE)
      return
    }
    show(editor, snapshot.oneBasedLine, MrPopupKind.NewThread(snapshot), model, emptyList())
  }

  /** UI thread, stop hook. Closes every popup without prompting; the count is all that is logged. */
  fun discardAll() {
    val popups = open.values.map { it.popup }
    open.clear()
    popups.forEach { popup -> guardedUi("discard") { popup.discard() } }
    if (popups.isNotEmpty()) logger.info("threadPopups discarded: count=${popups.size}")
  }

  /** Design §9.2: one per window; a busy popup refuses the replacement; the same line is re-activated; otherwise close-with-prompt first. */
  private fun show(
    editor: ITextEditor,
    oneBasedLine: Int,
    kind: MrPopupKind,
    model: InlineThreadModel,
    threadIds: List<String>,
  ) {
    val window = editor.site.workbenchWindow
    val current = open[window]?.takeIf { it.popup.isOpen }
    if (current != null) {
      if (current.popup.state.busy) {
        NotificationUtils.showOnUiThread(BUSY_MESSAGE)
        return
      }
      val sameLine = current.editor === editor && current.popup.oneBasedLine == oneBasedLine
      if (sameLine && kind is MrPopupKind.ExistingThreads && current.kind is MrPopupKind.ExistingThreads) {
        current.threadIds = threadIds
        current.popup.update(model)
        current.popup.activate()
        return
      }
      // Another line, editor or kind — and a new-thread request for the same line too: its
      // LineSnapshot must be the one frozen now, never the earlier popup's (design §9.3.2).
      current.popup.close() // preserves its drafts; its host's onClosed drops the entry
    }
    val input = editor.editorInput
    val document = editor.documentProvider?.getDocument(input)
    lateinit var popup: InlineThreadPopup
    val host = MrThreadPopupHost(
      kind = kind,
      sessionNow = { ReviewSessionRegistry.snapshotFor(editor) },
      writes = writes,
      newLauncher = { reload -> launcherFor(window, reload) },
      runOnUi = { block -> currentDisplay.asyncExec { block() } },
      currentEpoch = { DiscussionGenerationRegistry.currentEpoch },
      refreshSession = { identity, ref -> refreshOrBegin(editor, input, document, identity, ref) },
      reloadSidebar = { identity ->
        // The outcome is not awaited and never unlocks anything: the editor path reports Skipped itself.
        val target = with(identity) { DiscussionWriteTarget(instanceUrl, authFingerprint, projectId, mrIid) }
        reloadDiscussionsFor(window, target) { }
      },
      preserveDraft = { draft -> showCopyTextDialog(window, DIALOG_TITLE, UNSENT_DRAFT_MESSAGE, draft) },
      log = { message -> logger.info(message) },
      onPopupClosed = { if (open[window]?.popup === popup) open.remove(window) },
    )
    popup = InlineThreadPopup(editor, oneBasedLine, host)
    open[window] = Open(popup, editor, document, kind, threadIds)
    popup.open(model)
  }

  /** The launcher of one launch, bound to the workbench like `discussionWriteLauncher` but with the editor-path [reload]. */
  private fun launcherFor(
    window: IWorkbenchWindow?,
    reload: (onOutcome: (LoadOutcome) -> Unit) -> Unit,
  ) = DiscussionWriteLauncher(
    runInBackground = { block -> coroutineScope.launch { block() } },
    runOnUi = { block -> currentDisplay.asyncExec { block() } },
    reload = reload,
    // showOnUiThread, not show: same UI turn as the decision that authorised it (see discussionWriteLauncher).
    notify = { message -> NotificationUtils.showOnUiThread(message) },
    promptRetry = { message, body, onRetry ->
      promptForBody(window, DIALOG_TITLE, RETRY_PROMPT, body, message, RETRY_LABEL, onRetry)
    },
    promptSendAgain = { message, body, onSendAgain ->
      promptForBody(window, DIALOG_TITLE, SEND_AGAIN_PROMPT, body, message, SEND_AGAIN_LABEL, onSendAgain)
    },
    promptCopyText = { message, body -> showCopyTextDialog(window, DIALOG_TITLE, message, body) },
    log = { message -> logger.info(message) },
  )

  /**
   * UI thread, from the editor-path reload (design §9.3.1, FR-10): refresh the identity's session,
   * or establish it when the document has none (A8 / A18) — provided the editor is still alive and
   * still shows the [openedInput] and [openedDocument] its popup was opened on. A write that lands
   * after an input change must not attach the old MR/path session to the new document.
   */
  private fun refreshOrBegin(
    editor: ITextEditor,
    openedInput: IEditorInput?,
    openedDocument: IDocument?,
    identity: SessionIdentity,
    ref: MergeRequestRef,
  ) {
    val control = editor.getAdapter(Control::class.java)
    val currentInput = editor.editorInput
    val stillShown = editorStillShows(
      alive = control != null && !control.isDisposed,
      openedInput = openedInput,
      openedDocument = openedDocument,
      currentInput = currentInput,
      currentDocument = currentInput?.let { editor.documentProvider?.getDocument(it) },
    )
    if (!stillShown) {
      logger.info("threadPopup reload skipped: the editor is gone or shows another input.")
      return
    }
    if (ReviewSessionRegistry.refresh(editor, identity)) return
    ReviewSessionRegistry.begin(editor, ref, identity.newPath, identity.headSha)
  }

  /**
   * A reload landed (FR-10): re-map the popups of that document by the reply ids they show (never by
   * their anchor line, which is the clicked live line); a popup whose threads all vanished closes
   * (with its drafts preserved).
   */
  private fun applySnapshot(document: IDocument, snapshot: ReviewSessionSnapshot) {
    open.values.filter { it.document == document && it.popup.isOpen }.forEach { entry ->
      when (entry.kind) {
        is MrPopupKind.NewThread -> Unit
        is MrPopupKind.ExistingThreads -> {
          val model = MrThreadModelMapper.threadsWithIds(snapshot, entry.threadIds)
          if (model == null) entry.popup.close() else entry.popup.update(model)
        }
      }
    }
  }

  private fun closeWhere(matches: (Open) -> Boolean) {
    open.values.filter(matches).forEach { entry -> guardedUi("close") { entry.popup.close() } }
  }

  private inline fun guardedUi(step: String, block: () -> Unit) {
    try {
      block()
    } catch (e: Exception) {
      logger.error("threadPopup $step failed: exceptionType=${e.javaClass.name}")
    }
  }

  private const val DIALOG_TITLE = "Merge Request Thread"
  const val NO_SESSION_MESSAGE = "No merge request threads are loaded for this file."
  const val NO_THREAD_MESSAGE = "There is no merge request thread on this line."
  const val BUSY_MESSAGE = "A comment is still being sent from the open thread popup; wait for it to finish."
  const val UNSENT_DRAFT_MESSAGE =
    "The thread popup was closed with an unsent comment. Copy it from here if you want to keep it."
}

/**
 * Whether the editor-path reload may refresh or begin a session on the popup's editor (Codex r3):
 * only while the editor is [alive] and still shows the [openedInput] and the [openedDocument]
 * instance captured when the popup opened. After an input change the old MR/path session must not
 * be attached to the editor's new document. SWT-free.
 */
internal fun editorStillShows(
  alive: Boolean,
  openedInput: Any?,
  openedDocument: Any?,
  currentInput: Any?,
  currentDocument: Any?,
): Boolean = alive && openedInput != null && openedDocument != null &&
  currentInput == openedInput && currentDocument === openedDocument
