package com.gitlab.eclipse.chat

import com.gitlab.eclipse.chat.webview.ChatWebviewEntry

/**
 * Whether a chat webview id is currently usable, and why not if it isn't.
 */
data class ChatAvailability(val id: String, val enabled: Boolean, val disabledReason: String?)

/**
 * Decides which chat webview id should be initially selected among the candidates advertised by
 * the Language Server, given each candidate's [ChatAvailability] and the previously saved
 * selection (if any).
 *
 * Rules, in order:
 * 1. If [saved] is one of the candidates and enabled, it wins.
 * 2. Otherwise, among the enabled candidates, classic (`duo-chat-v2`) is preferred if enabled;
 *    else the first enabled candidate (in advertised order).
 * 3. If none are enabled, the first candidate (in advertised order) is returned.
 * 4. If there are no candidates, `null` is returned.
 */
object ChatSelectionResolver {
  private const val CLASSIC = "duo-chat-v2"

  fun resolve(
    candidates: List<ChatWebviewEntry>,
    availability: Map<String, ChatAvailability>,
    saved: String?,
  ): String? {
    if (candidates.isEmpty()) return null
    fun isEnabled(id: String) = availability[id]?.enabled == true

    if (saved != null && candidates.any { it.id == saved } && isEnabled(saved)) return saved

    val enabledIds = candidates.map { it.id }.filter { isEnabled(it) }
    if (enabledIds.isNotEmpty()) return if (CLASSIC in enabledIds) CLASSIC else enabledIds.first()

    return candidates.first().id
  }
}
