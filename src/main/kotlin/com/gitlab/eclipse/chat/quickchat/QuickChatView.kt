package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.views.inlinethread.InlineThreadModel
import com.gitlab.eclipse.views.inlinethread.SubmitTicket
import java.io.File

/**
 * What a [QuickChatSession] needs from its popup (design §8.1). PR-2's SWT host implements it; every
 * call arrives on the UI thread.
 */
interface QuickChatView {
  /** Shows [model] (the whole conversation pane). */
  fun render(model: InlineThreadModel)

  /** Ends [ticket]'s submit; [succeeded] clears the draft, otherwise the draft stays (FR-8). */
  fun released(ticket: SubmitTicket, succeeded: Boolean)
}

/** A scheduled UI action that can still be called off (a `Display.timerExec` in PR-2). */
fun interface Cancellable {
  fun cancel()
}

/**
 * The context fixed on the UI thread for one question (design §9.2.1): the size-checked [result] and
 * the anchor file's location for the preflight ([anchorFile] null when the input has none).
 */
data class CapturedContext(val result: ContextResult, val anchorFile: File?)
