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

  /**
   * A view waiting for the latch, together with the connection it was issued on. Design §11's
   * premise is that work started on one connection is not applied to another, and the view alone
   * cannot say which one it started on. Null [origin] means there was no connection at the time.
   */
  private data class PendingView(val view: String, val origin: LanguageServerSession?)

  /** One slot, latest-wins (design §7.4). */
  private var pending: PendingView? = null

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
      // Scheduled before the send, not after: see [deliver].
      scheduleResend(view, commandGeneration, RESENDS)
      deliver(view, snapshot.proxy)
      return
    }

    val waiting = PendingView(view, snapshot?.session)
    pending = waiting
    armReadinessDeadline(waiting.origin)
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

    val waiting = pending ?: return
    // The guard above says the *sender* is current; it says nothing about where the waiting view
    // came from. A view issued on a connection that has since been replaced is discarded on exactly
    // the condition the deadline's quiet outcome uses, so the two agree. A view issued while no
    // connection was current belongs to none, so any connection may carry it — that is why the
    // deadline reports it undelivered when it expires rather than refusing to deliver it at all.
    if (waiting.origin != null && waiting.origin !== snapshot.session) return discardQuietly()

    pending = null
    // Scheduled before the send, not after: see [deliver].
    scheduleResend(waiting.view, commandGeneration, RESENDS)
    deliver(waiting.view, snapshot.proxy)
  }

  /** Closes the latch for a webview that was just (re)created and has yet to report itself ready. */
  fun markNotReady() {
    readySession = null
    commandGeneration++
    // A view that is still waiting keeps waiting, so it needs a deadline again: the generation
    // above has just expired the one it had, and nothing else guarantees it reaches a terminal
    // state (design §12's language-server-session-mismatch row). The deadline is re-armed on the
    // connection the view was issued on, not on whichever is current now — that is what the
    // deadline's outcomes are written against.
    val waiting = pending ?: return
    armReadinessDeadline(waiting.origin)
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
      // No connection is current. That covers two states, not one: the connection this command
      // started on is gone, and — because this arm is reached first — the command having had no
      // connection to start on either. Neither is the quiet outcome below.
      current == null -> reportUndelivered()
      // A connection is current, but this command never had one to be sent on: the KDoc's fifth
      // outcome, reached only once the arm above has ruled out `current == null`.
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

    // Scheduled before the send, not after: see [deliver]. At this point in the series that costs
    // the tail rather than the whole series, which is the same loss one step smaller.
    scheduleResend(view, generation, remaining - 1)
    deliver(view, snapshot.proxy)
  }

  /**
   * Every caller schedules the resend series **before** calling this, so that a send which throws
   * does not take with it the mechanism that exists to cover a send that did not land. Nothing is
   * caught here: containing it would hide the failure without repairing it, and the resend is the
   * repair. The count is unaffected — design §13's three sends are three *attempts*.
   *
   * lsp4j does not currently make this reachable: `RemoteEndpoint.notify` catches `Exception` around
   * `MessageConsumer.consume` and only logs "Failed to send notification message.", so a broken pipe
   * or a serialisation failure never reaches this frame. The ordering is a property of this class,
   * not a repair of an observed failure.
   */
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
