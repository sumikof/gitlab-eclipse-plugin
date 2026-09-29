package com.gitlab.eclipse.chat.quickchat

/**
 * Classifies raw Quick Chat popup input into `/clear`, `/reset`, or an ordinary question
 * (design §8.1).
 *
 * Classification compares `trim().lowercase()`, matching the reference implementation (design
 * §6.4 R5), so surrounding whitespace and letter case never change which command is recognized.
 * A [Question] keeps the raw, unmodified text — only the slash-command check is normalized.
 */
sealed interface QuickChatCommand {
  /** Clears the conversation pane (design §9.4). */
  data object Clear : QuickChatCommand {
    /** The exact `question` value sent for M2 when a `threadId` exists (design §9.4). */
    const val literal: String = "/clear"
  }

  /** Starts a new conversation while keeping the pane's visible history (design §9.4). */
  data object Reset : QuickChatCommand {
    /** The exact `question` value sent for M2 when a `threadId` exists (design §9.4). */
    const val literal: String = "/reset"
  }

  /** An ordinary question, carried verbatim (not trimmed or lowercased). */
  data class Question(val text: String) : QuickChatCommand

  companion object {
    /** Classifies [input] per design §6.4 R5. */
    fun classify(input: String): QuickChatCommand =
      when (input.trim().lowercase()) {
        Clear.literal -> Clear
        Reset.literal -> Reset
        else -> Question(input)
      }
  }
}
