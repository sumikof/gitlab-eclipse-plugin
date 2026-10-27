package com.gitlab.eclipse.chat.quickchat.fix

/** What one Fix sends: the popup's editor, its anchor line, and the `/fix` message. */
data class FixRequest<E>(val editor: E, val oneBasedLine: Int, val message: String)

/**
 * Design §6, like openQuickChat (OpenQuickChatHandler.kt:17-31): re-checks availability first
 * (a reason → one notification, nothing else), then [prepare] (open / select; null → nothing),
 * then [send].
 */
fun <E> fixWithDuo(
  unavailableReason: () -> String?,
  notify: (String) -> Unit,
  prepare: () -> FixRequest<E>?,
  send: (FixRequest<E>) -> Unit,
) {
  unavailableReason()?.let { notify(it); return }
  prepare()?.let(send)
}
