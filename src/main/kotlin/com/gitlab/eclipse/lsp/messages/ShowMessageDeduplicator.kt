package com.gitlab.eclipse.lsp.messages

import org.eclipse.lsp4j.MessageType

private const val DEFAULT_WINDOW_MILLIS = 30_000L

/**
 * Decides whether a `window/showMessage` is worth showing (design §6, AC-M4): an identical
 * (session, type, text) message is shown once within [windowMillis], measured from the last time it
 * was shown. Sessions compare by reference. Pure logic, thread-safe; entries older than the window
 * are pruned on every call, so the memory it keeps is bounded by the messages of the last window.
 */
class ShowMessageDeduplicator(
  private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
  private val nowMillis: () -> Long = System::currentTimeMillis,
) {
  private val lastShown = HashMap<Key, Long>()

  fun shouldShow(session: Any, type: MessageType, text: String): Boolean {
    val now = nowMillis()
    val key = Key(session, type, text)
    synchronized(lastShown) {
      lastShown.values.removeIf { now - it >= windowMillis }
      if (key in lastShown) return false
      lastShown[key] = now
      return true
    }
  }

  /** Identity of [session], equality of the rest. */
  private class Key(val session: Any, val type: MessageType, val text: String) {
    override fun equals(other: Any?): Boolean =
      other is Key && other.session === session && other.type == type && other.text == text

    override fun hashCode(): Int = (System.identityHashCode(session) * 31 + type.hashCode()) * 31 + text.hashCode()
  }
}
