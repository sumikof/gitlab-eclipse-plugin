package com.gitlab.eclipse.views.webview.agentplatform

import com.gitlab.eclipse.utils.logger

/** Seam over the instance-scope preference store (production: the Koin `ScopedPreferenceStore`). */
interface LayoutPreferenceAccess {
  fun read(): String?
  fun write(value: String)
}

/**
 * The persisted Agent Platform layout plus change notification.
 *
 * UI-thread-only by contract, so there is no synchronization. Writes come only from our own
 * callers, so no preference change listener is registered; external edits of the key are not
 * supported. Listeners receive the value fixed at notify time: a listener that calls [set]
 * re-entrantly does not alter what the remaining listeners of the current notification see; the
 * new value is delivered afterwards.
 */
class AgentPlatformLayoutStore(private val access: LayoutPreferenceAccess) {
  private val listeners = mutableListOf<(AgentPlatformLayout) -> Unit>()
  private val pending = ArrayDeque<AgentPlatformLayout>()
  private var delivering = false

  fun current(): AgentPlatformLayout = AgentPlatformLayout.fromPreference(access.read())

  /** No-op (no write, no notify) when equal to [current]. */
  fun set(layout: AgentPlatformLayout) {
    if (layout == current()) return
    access.write(layout.preferenceValue)
    pending.addLast(layout)
    if (delivering) return
    delivering = true
    try {
      while (pending.isNotEmpty()) notify(pending.removeFirst())
    } finally {
      // Only an Error from a listener ends the loop early; what it left queued must not be
      // delivered, stale, by the next set().
      pending.clear()
      delivering = false
    }
  }

  /** Returns a handle whose `close()` removes the listener; closing twice is a no-op. */
  fun addListener(listener: (AgentPlatformLayout) -> Unit): AutoCloseable {
    listeners += listener
    return AutoCloseable { listeners.remove(listener) }
  }

  private fun notify(layout: AgentPlatformLayout) {
    for (listener in listeners.toList()) {
      try {
        listener(layout)
      } catch (e: Exception) {
        // Class name only: a listener's message could carry anything.
        logger<AgentPlatformLayoutStore>().warn("Agent Platform layout listener failed: ${e::class.java.name}")
      }
    }
  }
}
