package com.gitlab.eclipse.chat.quickchat

import java.util.concurrent.atomic.AtomicReference

/**
 * Whether one send's `aiAction` has gone out (design §12.4) — the only mutable state the UI thread
 * and the background share. Every transition is one compare-and-set, so "the UI gave up first"
 * and "the background started sending first" can never both win.
 */
class SendGate {
  sealed interface State {
    /** `aiAction` not sent yet (initial). */
    data object Open : State

    /** `aiAction` started; its result is unknown. */
    data object Sending : State

    /** `aiAction` succeeded; [update] is what the conversation must keep even if the answer never comes. */
    data class Sent(val update: BindingUpdate) : State

    /** The UI finished this send before it was sent; it must never be sent. */
    data object Closed : State
  }

  private val ref = AtomicReference<State>(State.Open)

  val state: State get() = ref.get()

  /** Background (w4): `Open → Sending`. False means the UI already finished this send — do not send. */
  fun tryBeginSend(): Boolean = ref.compareAndSet(State.Open, State.Sending)

  /** Background (w5): `Sending → Sent(update)`. False (a contract violation) leaves the state unchanged. */
  fun markSent(update: BindingUpdate): Boolean = ref.compareAndSet(State.Sending, State.Sent(update))

  /** UI: `Open → Closed`. Returns the state just before, which decides how a deadline is reported. */
  fun closeIfOpen(): State {
    while (true) {
      val current = ref.get()
      if (current != State.Open) return current
      if (ref.compareAndSet(State.Open, State.Closed)) return State.Open
    }
  }
}
