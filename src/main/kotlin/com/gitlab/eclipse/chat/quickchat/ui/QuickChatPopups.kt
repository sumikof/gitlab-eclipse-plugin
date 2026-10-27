package com.gitlab.eclipse.chat.quickchat.ui

import com.gitlab.eclipse.chat.DuoChatStateService
import com.gitlab.eclipse.chat.quickchat.QuickChatConversation
import com.gitlab.eclipse.chat.quickchat.QuickChatRuntime
import com.gitlab.eclipse.chat.quickchat.QuickChatService
import com.gitlab.eclipse.chat.quickchat.QuickChatSession
import com.gitlab.eclipse.chat.quickchat.duoChatAvailability
import com.gitlab.eclipse.chat.quickchat.toInlineModel
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.mergerequests.discussions.actions.showCopyTextDialog
import com.gitlab.eclipse.utils.CodeFormatter
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.inlinethread.InlineThreadPopup
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.texteditor.ITextEditor
import java.util.concurrent.CopyOnWriteArrayList

/** What [decidePlacement] does with an open request (design §9.1 step 4). */
enum class PopupPlacement {
  /** The window has no Quick Chat: open one. */
  NEW,

  /** The same editor and line: bring the open popup to the front. */
  ACTIVATE,

  /** Anything else: close the open popup (drafts offered; even while busy, FR-3), then open one. */
  REPLACE,
}

/** Where a window's open Quick Chat sits: its editor (compared by identity) and anchor line. */
data class OpenPlacement(val editor: Any, val oneBasedLine: Int)

/** Design §9.1 step 4, SWT-free. The editor is compared by identity: equal inputs in two editors are two places. */
fun decidePlacement(existing: OpenPlacement?, editor: Any, oneBasedLine: Int): PopupPlacement = when {
  existing == null -> PopupPlacement.NEW
  existing.editor === editor && existing.oneBasedLine == oneBasedLine -> PopupPlacement.ACTIVATE
  else -> PopupPlacement.REPLACE
}

/**
 * The SWT-free part of [QuickChatPopups.openAndSend]: sends [message] only to a popup that really
 * opened and is still open (create returns null when the shell failed to open). True when [submit]
 * accepted it (Fable N3).
 */
internal fun <P> sendIfOpened(popup: P?, isOpen: (P) -> Boolean, submit: (P, String) -> Boolean, message: String): Boolean =
  popup != null && isOpen(popup) && submit(popup, message)

/**
 * A thread-safe snapshot of the windows that show a Quick Chat, for readers that may run off the
 * UI thread (the close command's enablement, design §11.1). [publish] runs on the UI thread
 * whenever the popups change, and tells the listeners there — only when the set really changed.
 * A failing listener is reported to [onListenerFailure] and does not stop the others.
 */
class OpenWindows<W : Any>(private val onListenerFailure: (Throwable) -> Unit = {}) {
  @Volatile
  var current: Set<W> = emptySet()
    private set

  private val listeners = CopyOnWriteArrayList<() -> Unit>()

  fun addListener(listener: () -> Unit) {
    listeners += listener
  }

  fun removeListener(listener: () -> Unit) {
    listeners.remove(listener)
  }

  /** UI thread. Stores an immutable copy of [windows]. */
  fun publish(windows: Set<W>) {
    val next = windows.toSet()
    if (next == current) return
    current = next
    listeners.forEach { listener -> runCatching(listener).onFailure(onListenerFailure) }
  }
}

/**
 * The Quick Chat popups of the workbench (design §8.2, §9.1, §9.5): **at most one per workbench
 * window**, each with its own conversation and [QuickChatSession]. Holds no scope or executor — the
 * sessions use the Koin [QuickChatRuntime]. **UI thread only.**
 *
 * [discardAll] runs from the bundle stop hook, before the runtime's scope is cancelled (design §17):
 * every popup is discarded without a copy-text prompt and each session ended.
 */
object QuickChatPopups {
  private val logger by lazy { logger<QuickChatPopups>() }

  private class Open(val popup: InlineThreadPopup, val editor: ITextEditor)

  private val open = HashMap<IWorkbenchWindow, Open>()

  /** The windows of [open], readable from any thread; republished on every change of [open]. */
  val openWindows = OpenWindows<IWorkbenchWindow> { e ->
    logger.error("Quick Chat open-windows listener failed: exceptionType=${e.javaClass.name}")
  }

  /**
   * UI thread. Opens the Quick Chat of [editor]'s window at [oneBasedLine], re-activates it when it
   * is already there, or replaces the one elsewhere — after its unsent drafts were offered, since
   * the copy-text dialog is modal (design §9.5).
   */
  fun open(editor: ITextEditor, oneBasedLine: Int) {
    val window = editor.site.workbenchWindow
    val current = open[window]?.takeIf { it.popup.isOpen }
    val existing = current?.let { OpenPlacement(it.editor, it.popup.oneBasedLine) }
    when (decidePlacement(existing, editor, oneBasedLine)) {
      PopupPlacement.ACTIVATE -> {
        // FR-2: to the front with the focus in the input (activate() focuses it explicitly).
        current?.popup?.activate()
        return
      }
      PopupPlacement.REPLACE -> current?.popup?.close() // its host ends the session and drops the entry
      PopupPlacement.NEW -> Unit
    }
    create(window, editor, oneBasedLine)
  }

  /**
   * UI thread. Always replaces [editor]'s window's Quick Chat (L6) — a prepared question belongs in
   * a conversation of its own — then opens one at [oneBasedLine] and sends [message].
   */
  fun openAndSend(editor: ITextEditor, oneBasedLine: Int, message: String) {
    val window = editor.site.workbenchWindow
    open[window]?.takeIf { it.popup.isOpen }?.popup?.close()
    // close() may have offered the drafts in a modal dialog, whose nested event loop can close the
    // editor: a disposed text editor has no document provider any more.
    if (editor.editorInput == null || editor.documentProvider?.getDocument(editor.editorInput) == null) {
      logger.info("Quick Chat open-and-send skipped: the editor closed")
      return
    }
    val sent = sendIfOpened(create(window, editor, oneBasedLine), { it.isOpen }, { p, m -> p.submitDraft(m) }, message)
    if (!sent) logger.info("Quick Chat open-and-send did not send")
  }

  /** UI thread. Closes [window]'s Quick Chat (drafts offered); false when it has none. */
  fun closeIn(window: IWorkbenchWindow): Boolean {
    val current = open[window]?.takeIf { it.popup.isOpen } ?: return false
    current.popup.close()
    return true
  }

  /** UI thread. Whether [window] shows a Quick Chat. */
  fun isOpenIn(window: IWorkbenchWindow): Boolean = open[window]?.popup?.isOpen == true

  /** UI thread, stop hook. Discards every popup without prompting (each host ends its session). Never throws. */
  fun discardAll() {
    try {
      val popups = open.values.map { it.popup }
      open.clear()
      publish()
      popups.forEach { popup -> guarded("discard") { popup.discard() } }
      if (popups.isNotEmpty()) logger.info("Quick Chat popups discarded: count=${popups.size}")
    } catch (e: Exception) {
      runCatching { logger.error("Quick Chat discardAll failed: exceptionType=${e.javaClass.name}") }
    }
  }

  /** Opens a Quick Chat in [window]; null when the shell could not be opened (nothing is left behind then). */
  private fun create(window: IWorkbenchWindow, editor: ITextEditor, oneBasedLine: Int): InlineThreadPopup? {
    val scheduler = DisplayUiScheduler(editor.site.shell.display)
    val conversation = QuickChatConversation()
    val codeActions = QuickChatCodeActions(insert = QuickChatSnippetInserter(service<CodeFormatter>())::insert)
    lateinit var popup: InlineThreadPopup
    val host = QuickChatHost(
      preserveDraft = { draft ->
        showCopyTextDialog(window, QuickChatUiTexts.DIALOG_TITLE, QuickChatUiTexts.UNSENT_DRAFT_MESSAGE, draft)
      },
      onPopupClosed = { removeIfCurrent(window, popup) },
      // Design §9.6: Insert goes into this popup's editor, never the active one.
      codeAction = { action, code -> codeActions.perform(editor, action, code) },
    )
    val session = QuickChatSession(
      conversation = conversation,
      runtime = service<QuickChatRuntime>(),
      service = service<QuickChatService>(),
      contextSource = { question -> captureContext(editor, question) },
      availability = duoChatAvailability(service<DuoChatStateService>()),
      view = host,
      runOnUi = uiHop(scheduler) { message -> logger.warn(message) },
      scheduleOnUi = uiTimer(scheduler),
    )
    popup = InlineThreadPopup(
      editor,
      oneBasedLine,
      host,
      title = QuickChatUiTexts.popupTitle(oneBasedLine),
      submitOnModEnter = true,
      // Closing ends the send and drops its result: nothing else would keep the question (design §9.5).
      preserveInFlightDraft = true,
    )
    host.bind(session, popup)
    open[window] = Open(popup, editor)
    publish()
    try {
      popup.open(conversation.toInlineModel())
      return popup
    } catch (e: Exception) {
      // A half-built shell must not stay in the map or keep a session.
      logger.error("Quick Chat popup open failed: exceptionType=${e.javaClass.name}")
      guarded("discard") { popup.discard() }
      removeIfCurrent(window, popup)
      guarded("end") { session.end() }
      return null
    }
  }

  private fun removeIfCurrent(window: IWorkbenchWindow, popup: InlineThreadPopup) {
    if (open[window]?.popup !== popup) return
    open.remove(window)
    publish()
  }

  private fun publish() {
    openWindows.publish(open.keys)
  }

  private inline fun guarded(step: String, block: () -> Unit) {
    try {
      block()
    } catch (e: Exception) {
      logger.error("Quick Chat popup $step failed: exceptionType=${e.javaClass.name}")
    }
  }
}
