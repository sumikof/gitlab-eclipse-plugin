package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.inlinethread.SubmitTicket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The only way one send's background reaches the UI (design §9.2.5), and its only reference to the
 * session: [detach] cuts it when the send ends, so an abandoned job that never returns (§15.3) keeps
 * neither the session nor its view or editor alive.
 *
 * Every action posted with [runOnUi] captures this sink and the outcome — never the session — and
 * reads the session only when it runs on the UI thread. An action posted just before [detach] thus
 * finds nothing and does nothing (Codex round 8 #1, A33).
 */
class ResultSink(
  session: QuickChatSession,
  private val ticket: SubmitTicket,
  private val gen: Long,
  private val runOnUi: (() -> Unit) -> Unit,
) {
  private val session = AtomicReference<QuickChatSession?>(session)
  private val delivered = AtomicBoolean(false)

  /** Background (w7): hands [outcome] to the UI. */
  fun deliver(outcome: QuickChatOutcome) {
    delivered.set(true)
    post(outcome)
  }

  /** The job's completion handler (design §9.2.4 (c)): ends the send if nothing was delivered. */
  fun completed() {
    if (!delivered.get()) post(QuickChatOutcome.Interrupted)
  }

  /** UI thread: the body of every posted action. */
  fun onUi(outcome: QuickChatOutcome) {
    session.get()?.finishOnce(ticket, gen, outcome)
  }

  /** UI thread (`finishOnce`, `end`): nothing reaches the session from here on. */
  fun detach() {
    session.set(null)
  }

  // Runs on a background thread or inside a completion handler: it must never throw.
  private fun post(outcome: QuickChatOutcome) {
    try {
      runOnUi { onUi(outcome) }
    } catch (e: Exception) {
      logger<ResultSink>().info("Quick Chat result not posted to the UI: ${e.javaClass.name}")
    }
  }
}
