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
  private val latest = AtomicReference<(() -> String)?>(null)
  private val progressPosted = AtomicBoolean(false)

  /** Background (w7): hands [outcome] to the UI. */
  fun deliver(outcome: QuickChatOutcome) {
    delivered.set(true)
    post(outcome)
  }

  /** The job's completion handler (design §9.2.4 (c)): ends the send if nothing was delivered. */
  fun completed() {
    if (!delivered.get()) post(QuickChatOutcome.Interrupted)
  }

  /**
   * Background, from the stream (streaming design §9.4 step 1): keeps only the latest [source] and
   * posts at most one UI action at a time, so a flood of chunks never floods the UI queue. May be
   * called concurrently and after the send ended; never throws.
   *
   * A callback that arrives while a post is being attempted leaves the posting to it. Should that
   * post fail, the newer source would be stranded until a third callback, so a failed post is
   * retried once when [latest] changed meanwhile (Codex PR #105 P2) — once only: a disposed display
   * fails every time and must not spin.
   */
  fun progress(source: () -> String) {
    if (session.get() == null) return
    latest.set(source)
    if (!progressPosted.compareAndSet(false, true)) return
    if (tryPostProgress()) return
    if (latest.get() !== source && progressPosted.compareAndSet(false, true)) tryPostProgress()
  }

  /** Posts the progress action; on failure clears the in-flight flag so a later callback can post. */
  private fun tryPostProgress(): Boolean =
    try {
      runOnUi { onProgressUi() }
      true
    } catch (e: Exception) {
      progressPosted.set(false)
      logger<ResultSink>().info("Quick Chat progress not posted to the UI: ${e.javaClass.name}")
      false
    }

  /**
   * UI thread: re-reads the session; the text itself is read later, once per render. The flag is
   * reset before [latest] is read, so a progress arriving in between posts again instead of being lost.
   */
  private fun onProgressUi() {
    progressPosted.set(false)
    val source = latest.get() ?: return
    session.get()?.onProgress(ticket, gen, source)
  }

  /** UI thread: the body of every posted action. */
  fun onUi(outcome: QuickChatOutcome) {
    session.get()?.finishOnce(ticket, gen, outcome)
  }

  /** UI thread (`finishOnce`, `end`): nothing reaches the session from here on. */
  fun detach() {
    session.set(null)
    latest.set(null) // lets go of the stream's text reader
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
