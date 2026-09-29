package com.gitlab.eclipse.chat.quickchat.ui

import com.gitlab.eclipse.chat.quickchat.QuickChatSession
import com.gitlab.eclipse.chat.quickchat.QuickChatView
import com.gitlab.eclipse.views.inlinethread.CodeBlockAction
import com.gitlab.eclipse.views.inlinethread.InlineThreadAction
import com.gitlab.eclipse.views.inlinethread.InlineThreadHost
import com.gitlab.eclipse.views.inlinethread.InlineThreadModel
import com.gitlab.eclipse.views.inlinethread.InlineThreadSurface
import com.gitlab.eclipse.views.inlinethread.SubmitTicket

/**
 * The thin adapter between one Quick Chat popup and its [QuickChatSession] (design §8.2): the
 * popup's [InlineThreadHost] and the session's [QuickChatView]. SWT-free; **UI thread only**.
 *
 * The session and the popup each need the other, so the host is built first and [bind] joins them
 * before the popup opens (the same order as `MrThreadPopups.show`). Until then a submit is refused
 * and a render or release is dropped.
 *
 * @param preserveDraft the copy-text dialog for one unsent draft when the popup closes (§9.5).
 * @param onPopupClosed drops the popup from `QuickChatPopups`; runs after the session ended.
 * @param codeAction a code block's Copy / Insert with that block's code (design §9.6, §9.8).
 */
class QuickChatHost(
  private val preserveDraft: (String) -> Unit,
  private val onPopupClosed: () -> Unit,
  private val codeAction: (CodeBlockAction, String) -> Unit,
) : InlineThreadHost, QuickChatView {
  private var session: QuickChatSession? = null
  private var surface: InlineThreadSurface? = null

  /** Joins the popup's [session] and [surface]; once, before the popup opens. */
  fun bind(session: QuickChatSession, surface: InlineThreadSurface) {
    this.session = session
    this.surface = surface
  }

  /** The popup's own ticket object goes to the session unchanged: the session matches it by identity. */
  override fun onSubmit(surface: InlineThreadSurface, ticket: SubmitTicket) {
    val current = session
    if (current == null) {
      surface.state.onLaunchRejected(ticket)
      surface.refresh()
      return
    }
    current.submit(ticket)
  }

  /** Quick Chat has no Resolve / Unresolve; REPLY is its submit and never arrives here. */
  override fun onAction(surface: InlineThreadSurface, action: InlineThreadAction) = Unit

  override fun preserveDrafts(drafts: List<String>) {
    drafts.forEach(preserveDraft)
  }

  /** Design §9.5 step 2: end the conversation's send, then leave the window's map — even if ending failed. */
  override fun onClosed() {
    try {
      session?.end()
    } finally {
      onPopupClosed()
    }
  }

  override fun onCodeAction(surface: InlineThreadSurface, action: CodeBlockAction, code: String) {
    codeAction(action, code)
  }

  /** The whole pane; the popup scrolls rich entries to the newest one on every render (I6). */
  override fun render(model: InlineThreadModel) {
    surface?.update(model)
  }

  /**
   * Ends [ticket]'s submit (FR-8): a success clears its draft — only while the user has not typed
   * since Send, which the state checks — then busy is released and the widgets re-synced. Quick
   * Chat's item is never a new thread, so a success never closes the popup.
   */
  override fun released(ticket: SubmitTicket, succeeded: Boolean) {
    val current = surface ?: return
    if (succeeded) current.state.onSucceeded(ticket)
    current.state.onAttemptFinished(ticket)
    current.refresh()
  }
}
