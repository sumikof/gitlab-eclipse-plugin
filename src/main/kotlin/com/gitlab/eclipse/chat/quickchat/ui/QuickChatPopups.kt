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
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.inlinethread.InlineThreadPopup
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.texteditor.ITextEditor

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
      popups.forEach { popup -> guarded("discard") { popup.discard() } }
      if (popups.isNotEmpty()) logger.info("Quick Chat popups discarded: count=${popups.size}")
    } catch (e: Exception) {
      runCatching { logger.error("Quick Chat discardAll failed: exceptionType=${e.javaClass.name}") }
    }
  }

  private fun create(window: IWorkbenchWindow, editor: ITextEditor, oneBasedLine: Int) {
    val scheduler = DisplayUiScheduler(editor.site.shell.display)
    val conversation = QuickChatConversation()
    lateinit var popup: InlineThreadPopup
    val host = QuickChatHost(
      preserveDraft = { draft ->
        showCopyTextDialog(window, QuickChatUiTexts.DIALOG_TITLE, QuickChatUiTexts.UNSENT_DRAFT_MESSAGE, draft)
      },
      onPopupClosed = { if (open[window]?.popup === popup) open.remove(window) },
      // Copy / Insert (design §9.6, §9.8) are handled by the code-action handler that replaces this no-op.
      codeAction = { _, _ -> },
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
    )
    host.bind(session, popup)
    open[window] = Open(popup, editor)
    try {
      popup.open(conversation.toInlineModel())
    } catch (e: Exception) {
      // A half-built shell must not stay in the map or keep a session.
      logger.error("Quick Chat popup open failed: exceptionType=${e.javaClass.name}")
      guarded("discard") { popup.discard() }
      if (open[window]?.popup === popup) open.remove(window)
      guarded("end") { session.end() }
    }
  }

  private inline fun guarded(step: String, block: () -> Unit) {
    try {
      block()
    } catch (e: Exception) {
      logger.error("Quick Chat popup $step failed: exceptionType=${e.javaClass.name}")
    }
  }
}
