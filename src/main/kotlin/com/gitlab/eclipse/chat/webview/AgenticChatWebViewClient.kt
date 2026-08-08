package com.gitlab.eclipse.chat.webview

import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.lsp.plugins.messages.ExtensionToPluginNotification
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger

/**
 * Pushes `switchView` to the Agentic Duo Chat webview (`agentic-duo-chat`).
 *
 * Design §7.4 splits the delivery failure in two and gives each its own mechanism, because only one
 * of the two can be observed: the readiness deadline below reports that no `appReady` ever arrived,
 * while the bounded resend covers the window design §5.3a measured and reports nothing either way.
 *
 * This is deliberately not shaped like [GitLabDuoChatWebViewClient]: one slot instead of a list,
 * and a readiness latch instead of a focus gate (design §7.4 / §5.4).
 *
 * All state here is confined to the UI thread (design §15). The bus delivers `appReady` on a
 * dispatch thread, and [AgenticChatWebViewController] makes the hop before reaching [markReady].
 */
class AgenticChatWebViewClient(
  private val wrapper: GitLabLanguageServerWrapper,
  private val onUndelivered: (String) -> Unit,
  private val scheduleTimer: (Long, Runnable) -> Unit,
) {
  /** An overload rather than a default argument (design §7.4). */
  constructor(wrapper: GitLabLanguageServerWrapper, onUndelivered: (String) -> Unit) :
    this(wrapper, onUndelivered, { delayMillis, task -> currentDisplay.timerExec(delayMillis.toInt(), task) })

  private val logger = logger<AgenticChatWebViewClient>()

  /** The connection whose webview reported itself ready, or null while the latch is closed (§7.1a). */
  private var readySession: LanguageServerSession? = null

  /** One slot, latest-wins (design §7.4). */
  private var pending: String? = null

  /** Design §7.4a. Every callback scheduled below captures it and re-checks it when it runs. */
  private var commandGeneration: Long = 0

  /**
   * Sends [view] now if the latch is open for the current connection, and otherwise holds it in the
   * one slot (design §7.4).
   */
  fun switchView(view: String) {
    commandGeneration++
    val snapshot = wrapper.currentSnapshot

    if (snapshot != null && readySession === snapshot.session) {
      pending = null
      deliver(view, snapshot.proxy)
      scheduleResend(view, commandGeneration, RESENDS)
      return
    }

    pending = view
    armReadinessDeadline(snapshot?.session)
  }

  /**
   * Opens the latch for [session] and flushes whatever was waiting, but only while [session] is
   * still the current connection.
   *
   * [session] is the connection the `appReady` was sent from rather than whichever is current on
   * arrival, and storing it unconditionally would let a late one from a replaced connection close a
   * latch that is already open — design §7.4 has that sequence.
   */
  fun markReady(session: LanguageServerSession) {
    val snapshot = wrapper.currentSnapshot ?: return
    if (snapshot.session !== session) return

    readySession = session
    // Advanced on every match, not only when a view is flushed below. A restart that reuses the
    // Browser calls no [markNotReady], so without this the resend series of a command issued on the
    // previous connection would find both its generation and this newly stored latch agreeing with
    // the new connection, and would deliver to it.
    commandGeneration++

    val view = pending ?: return
    pending = null
    deliver(view, snapshot.proxy)
    scheduleResend(view, commandGeneration, RESENDS)
  }

  /** Closes the latch for a webview that was just (re)created and has yet to report itself ready. */
  fun markNotReady() {
    readySession = null
    commandGeneration++
    // A view that is still waiting keeps waiting, so it needs a deadline again: the generation
    // above has just expired the one it had, and nothing else guarantees it reaches a terminal
    // state (design §12's language-server-session-mismatch row).
    if (pending != null) armReadinessDeadline(wrapper.currentSnapshot?.session)
  }

  private fun armReadinessDeadline(captured: LanguageServerSession?) {
    val generation = commandGeneration
    scheduleTimer(READINESS_TIMEOUT_MILLIS) { onReadinessDeadline(generation, captured) }
  }

  /**
   * Design §7.4's timer table, plus a fifth outcome the table does not carry: the design document is
   * frozen, and the divergence is recorded in the PR body instead.
   *
   * The fifth outcome is a [captured] of null while a connection is now current, and it is a state
   * of its own rather than a loosening of the "no current connection" one: there, the connection the
   * command started on has since gone; here, the command never had one to be sent on. The quiet
   * outcome below assumes the user swapped connections themselves, which is true of neither, and
   * this one is known not to have been delivered — so reporting it cannot be the false alarm that
   * keeps the resend silent.
   */
  private fun onReadinessDeadline(generation: Long, captured: LanguageServerSession?) {
    if (generation != commandGeneration) return

    val current = wrapper.currentSnapshot?.session
    when {
      // The connection this command started on is gone.
      current == null -> reportUndelivered()
      // There was no connection to start on.
      captured == null -> reportUndelivered()
      // Another connection took over under it.
      current !== captured -> discardQuietly()
      // Still the same connection, which never reported itself ready.
      else -> reportUndelivered()
    }
  }

  /** Design §12's F-a row. */
  private fun reportUndelivered() {
    pending = null
    // §17: the surface and what went wrong, and nothing else.
    logger.error("Webview '${ChatWebviewCatalog.AGENTIC_WEBVIEW_ID}': switchView undelivered, category=not-ready")
    onUndelivered(UNDELIVERED_MESSAGE)
  }

  /** Design §12's session-mismatch row: discarded without notifying, recorded in the log only. */
  private fun discardQuietly() {
    pending = null
    logger.info("Webview '${ChatWebviewCatalog.AGENTIC_WEBVIEW_ID}': switchView dropped, category=session-changed")
  }

  private fun scheduleResend(view: String, generation: Long, remaining: Int) {
    if (remaining <= 0) return
    scheduleTimer(RESEND_INTERVAL_MILLIS) { resend(view, generation, remaining) }
  }

  /**
   * Design §7.4a's three conditions, and nothing at all if any of them fails. A closed latch is one
   * of the three, and it fails the comparison below: a session instance is never null, so a null
   * latch matches no current connection.
   */
  private fun resend(view: String, generation: Long, remaining: Int) {
    if (generation != commandGeneration) return

    val snapshot = wrapper.currentSnapshot ?: return
    if (readySession !== snapshot.session) return

    deliver(view, snapshot.proxy)
    scheduleResend(view, generation, remaining - 1)
  }

  private fun deliver(view: String, proxy: GitLabLanguageServer) {
    proxy.pluginNotification(
      ExtensionToPluginNotification(
        pluginId = ChatWebviewCatalog.AGENTIC_WEBVIEW_ID,
        type = "switchView",
        payload = mapOf("view" to view),
      )
    )
  }

  companion object {
    /**
     * Design §13's F-a row. Its own constant rather than one shared with the metadata timeout that
     * happens to hold the same value, for the reason design §7.1a gives.
     */
    private const val READINESS_TIMEOUT_MILLIS = 10_000L

    /** Design §13: two resends, three sends in all. */
    private const val RESENDS = 2

    /** Design §14 puts the window at a few hundred milliseconds and fixes no exact value. */
    private const val RESEND_INTERVAL_MILLIS = 300L

    internal const val UNDELIVERED_MESSAGE =
      "GitLab Duo Chat could not switch the view: the agentic chat did not become ready."
  }
}
