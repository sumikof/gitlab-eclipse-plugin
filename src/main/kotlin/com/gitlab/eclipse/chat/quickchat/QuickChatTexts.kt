package com.gitlab.eclipse.chat.quickchat

/** Every user-facing Quick Chat text of the SWT-free layer, in one place (design §14, I4). */
object QuickChatTexts {
  const val ITEM_TITLE = "GitLab Duo Quick Chat"
  const val INPUT_PLACEHOLDER = "Ask GitLab Duo a question. /clear or /reset starts a new chat."
  const val AUTHOR_YOU = "You"
  const val AUTHOR_DUO = "GitLab Duo"
  const val WAITING = "Waiting for the answer…"
  const val NEW_CHAT = "New chat"
  const val EARLIER_REMOVED = "(Earlier messages were removed)"
  const val ANSWER_TRUNCATED = "(Answer truncated)"
  const val SEND = "Send"

  const val UNAVAILABLE_DEFAULT = "GitLab Duo Chat is not available."
  const val SERVER_REJECTED_FALLBACK = "GitLab did not accept the question."
  const val EMPTY_ANSWER = "GitLab Duo returned an empty answer."
  const val SEND_FAILED = "Failed to send the question to GitLab"
  const val MAYBE_SENT = "The question may have been sent, but no answer could be retrieved."
  const val TIMED_OUT_AFTER_SEND = "Timed out waiting for the answer."
  const val TIMED_OUT_BEFORE_SEND = "GitLab did not respond in time. Nothing was sent."
  const val BUSY = "Earlier Quick Chat requests are still not responding. Try again later."
  const val INTERRUPTED = "The request was interrupted."
  const val CONNECTION_CHANGED = "The GitLab connection changed. Your next question starts a new chat."
  const val PROJECT_UNCONFIRMED = "Quick Chat could not confirm the GitLab project of this file, so nothing was sent."
  const val PROJECT_OTHER_INSTANCE =
    "This file belongs to a project on a different GitLab instance than the connected one, so nothing was sent."
  const val QUESTION_TOO_LONG = "The question is too long."
  const val SELECTION_TOO_LARGE = "The selection is too large for Quick Chat. Select less code."

  fun unsupported(version: String?): String =
    if (version == null) {
      "Quick Chat requires GitLab 17.10 or later."
    } else {
      "Quick Chat requires GitLab 17.10 or later (this instance: $version)."
    }

  fun sendFailed(status: Int?, correlationId: String?): String {
    val details = listOfNotNull(status?.let { "HTTP $it" }, correlationId?.let { "correlation ID $it" })
    return if (details.isEmpty()) "$SEND_FAILED." else "$SEND_FAILED (${details.joinToString(", ")})."
  }

  /** The conversation-pane text for an [outcome] that is not an answer (design §14). */
  fun failure(outcome: QuickChatOutcome): String = serverFailure(outcome) ?: localFailure(outcome)

  /** What GitLab (or the way to it) said; null for an outcome decided on this side. */
  private fun serverFailure(outcome: QuickChatOutcome): String? = when (outcome) {
    is QuickChatOutcome.Unavailable -> outcome.reason ?: UNAVAILABLE_DEFAULT
    is QuickChatOutcome.Unsupported -> unsupported(outcome.version)
    is QuickChatOutcome.ServerRejected ->
      outcome.messages.joinToString("\n").ifEmpty { SERVER_REJECTED_FALLBACK }
    is QuickChatOutcome.EmptyAnswer -> EMPTY_ANSWER
    is QuickChatOutcome.TransportFailed -> sendFailed(outcome.status, outcome.correlationId)
    is QuickChatOutcome.MaybeSent -> MAYBE_SENT
    is QuickChatOutcome.TimedOut -> if (outcome.beforeSend) TIMED_OUT_BEFORE_SEND else TIMED_OUT_AFTER_SEND
    else -> null
  }

  private fun localFailure(outcome: QuickChatOutcome): String = when (outcome) {
    QuickChatOutcome.Busy -> BUSY
    QuickChatOutcome.ConnectionChanged -> CONNECTION_CHANGED
    is QuickChatOutcome.ProjectCheckFailed ->
      if (outcome.kind == ProjectCheckKind.OTHER_INSTANCE) PROJECT_OTHER_INSTANCE else PROJECT_UNCONFIRMED
    is QuickChatOutcome.TooLarge ->
      if (outcome.item == TooLargeItem.QUESTION) QUESTION_TOO_LONG else SELECTION_TOO_LARGE
    // Interrupted and Failed; Answered and Cleared never get here.
    else -> INTERRUPTED
  }
}
