package com.gitlab.eclipse.chat.webview

import com.gitlab.eclipse.lsp.NewPromptRequest

/**
 * A snapshot of the transient chat intents taken at one selection resolution.
 *
 * Each field is the intent addressed to one surface: [focusRequested] and [classicPrompt] to the
 * classic webview, [agenticView] to the agentic one.
 */
data class PendingChatIntents(
  val focusRequested: Boolean = false,
  val classicPrompt: NewPromptRequest? = null,
  val agenticView: String? = null,
)

/** What [ChatIntentRouter] decided to hand to a chat client. */
sealed interface ChatIntentAction {
  /** A `newPrompt` for the classic client. */
  data class SendClassicPrompt(val payload: NewPromptRequest) : ChatIntentAction

  /** A `switchView` for the agentic client. */
  data class SwitchAgenticView(val view: String) : ChatIntentAction
}

/**
 * Decides which pending chat intents reach a client once a selection has resolved (design §7.4).
 *
 * It is split out of `LanguageServerBrowserView` for the reason design §7.2 gives for the same
 * split there: a `ViewPart` cannot be constructed headless, so a decision left inside one cannot be
 * tested. Only the decision lives here — no widget, no state, no side effect.
 */
object ChatIntentRouter {
  /** The prompt the classic webview understands as "take focus". */
  const val FOCUS_CHAT_PROMPT = "focusChat"

  /**
   * Routes [intents] for a resolution that ended up showing [shownId], or showing no chat at all
   * when it is null.
   *
   * An intent is routed only when the webview it addresses is the one shown, so a command aimed at
   * one surface is never handed to the other's client. An intent that is not routed is simply
   * absent from the result; dropping it is the caller's job, and design §7.4 has the caller drop
   * all of them whether or not they were routed.
   */
  fun route(shownId: String?, intents: PendingChatIntents): List<ChatIntentAction> = buildList {
    if (shownId == ChatWebviewCatalog.CLASSIC_WEBVIEW_ID) {
      if (intents.focusRequested) {
        add(ChatIntentAction.SendClassicPrompt(NewPromptRequest(prompt = FOCUS_CHAT_PROMPT)))
      }
      intents.classicPrompt?.let { add(ChatIntentAction.SendClassicPrompt(it)) }
    }

    if (shownId == ChatWebviewCatalog.AGENTIC_WEBVIEW_ID) {
      intents.agenticView?.let { add(ChatIntentAction.SwitchAgenticView(it)) }
    }
  }
}
