package com.gitlab.eclipse.mergerequests.discussions

import com.gitlab.eclipse.ci.actions.InFlightWriteGuard
import kotlinx.coroutines.CancellationException

/**
 * Launch-and-complete core of one discussion write (design §8.2, §9.2, §9.3): acquires the
 * per-target [InFlightWriteGuard] key, runs the injected `write` in the background, and settles
 * the outcome on the UI thread through exactly one of the five terminal branches. This is where
 * every protection against posting a duplicate comment and against losing typed text lives.
 *
 * **SWT-free by construction**: this container cannot run SWT, so every thread hop
 * ([runInBackground], [runOnUi]) and every UI effect ([reload], [notify], [promptRetry],
 * [promptSendAgain], [promptCopyText], [log]) arrives as an injected function, which keeps the
 * invariants below directly unit-testable. Do not import anything from `org.eclipse.swt` or
 * `org.eclipse.ui` here. A later task supplies the real implementations.
 *
 * **The anti-duplicate invariants** (design §9.2/§9.3 — each is proven by its own test):
 * - [DiscussionWriteOutcome.Ambiguous] NEVER offers `[Retry]`: the mutation may have committed,
 *   GraphQL mutations carry no idempotency key, and the in-flight guard cannot help because the
 *   first request already completed. Instead the thread is reloaded first.
 * - `[Send again]` is reachable from exactly one of the six reload results:
 *   [LoadOutcome.Applied] — the only one that means "the refreshed thread is now on screen", so
 *   the user can actually check whether the comment already landed. `Superseded` / `Failed` /
 *   `GateRejected` / `Skipped` and the never-invoked callback all mean the current state was NOT
 *   shown, so re-sending could duplicate a comment that did get posted; those paths only offer
 *   a copy-text dialog (or a plain notification when there is no text to preserve).
 * - The terminal applies the **lifecycle** guard ([registryActive] / [registryEpoch] vs the
 *   frozen `startEpoch`) but deliberately NOT the freshness guard
 *   ([DiscussionGenerationRegistry.isLatest]): if the user refreshes the MR while a write is in
 *   flight, a new generation is issued, and a freshness check would then discard this completion
 *   — a server-side success would never be re-fetched (stale tree) and a failure would never
 *   show its dialog (typed text lost). The lifecycle guard exists only to keep dialogs from
 *   popping up after the plugin has begun shutting down.
 *
 * @param reload force-reloads the discussions of the write's target and reports a [LoadOutcome];
 *   per [DiscussionsLoader]'s completion contract the callback may never be invoked (shutdown /
 *   view disposal), which must simply mean "no dialog appears".
 * @param promptRetry `[Retry]` dialog for a [DiscussionWriteOutcome.Definite] failure; its
 *   callback receives the (possibly edited) body to re-send.
 * @param promptSendAgain `[Send again]` dialog for an Ambiguous-then-Applied completion; its
 *   callback receives the (possibly edited) body to re-send.
 * @param promptCopyText text-preserving, non-resend dialog: the user can only copy what they
 *   typed, never re-send it from here.
 */
class DiscussionWriteLauncher(
  private val runInBackground: (() -> Unit) -> Unit,
  private val runOnUi: (() -> Unit) -> Unit,
  private val reload: (onOutcome: (LoadOutcome) -> Unit) -> Unit,
  private val notify: (String) -> Unit,
  private val promptRetry: (message: String, body: String, onRetry: (String) -> Unit) -> Unit,
  private val promptSendAgain: (message: String, body: String, onSendAgain: (String) -> Unit) -> Unit,
  private val promptCopyText: (message: String, body: String) -> Unit,
  private val log: (String) -> Unit,
  private val registryActive: () -> Boolean = { DiscussionGenerationRegistry.active },
  private val registryEpoch: () -> Long = { DiscussionGenerationRegistry.currentEpoch },
) {

  /**
   * Starts one write. Must be called on the UI thread, with [startEpoch] frozen from
   * [DiscussionGenerationRegistry.currentEpoch] in that same UI turn. [body] is `""` for
   * operations with no text input (resolve, delete); those use [notify] instead of the prompt
   * dialogs, because there is nothing to preserve (design §11.2).
   *
   * [write] receives **both** the body and the epoch of the attempt that is running:
   * - the body, so a `[Retry]` / `[Send again]` re-entry can send **edited** text;
   * - the epoch, so the re-entry's pre-send lifecycle check compares against the epoch [relaunch]
   *   just re-froze rather than the one the first attempt started with. A callback that closed over
   *   its own epoch would, after a stop→restart while the dialog was open, always compare stale,
   *   always return [DiscussionWriteOutcome.Aborted] — and Aborted shows no UI at all, so the text
   *   the user explicitly confirmed would vanish silently. Handlers must pass the parameter
   *   straight through and never capture an epoch of their own.
   */
  fun launch(
    key: DiscussionWriteKey,
    body: String,
    startEpoch: Long,
    write: (body: String, startEpoch: Long) -> DiscussionWriteOutcome,
  ) {
    if (!InFlightWriteGuard.tryAcquire(key)) {
      notify(ALREADY_IN_PROGRESS_MESSAGE)
      return
    }
    runInBackground {
      val outcome = try {
        write(body, startEpoch)
      } catch (e: CancellationException) {
        // Rethrown, never turned into an outcome: swallowing it would hide a cancelled
        // coroutine from its caller. The finally below still releases the key.
        throw e
      } catch (e: Throwable) {
        // Secret discipline: label + exceptionType only. Never cause.message (GraphQlException
        // interpolates server strings that can echo the submitted body), never responseBody,
        // never the exception object, never the body.
        // Wrapped for the same reason auditedDiscussionWrite wraps its audit line: a failing logger
        // must never derail outcome delivery. Unwrapped, a throw here escapes this catch, and the
        // terminal never runs -- no [Retry], and the text the user typed is lost.
        runCatching {
          log("discussionWrite outcome=escapedThrowable exceptionType=${e.javaClass.simpleName}")
        }
        // Definite, NOT Ambiguous: runDiscussionWrite already classifies everything that can go
        // wrong at or after the mutation and rethrows cancellation, so the only way a throwable
        // escapes it is from the connection gate — i.e. BEFORE anything was transmitted.
        // Classifying that as Ambiguous would tell the user "you may have already posted" about
        // a request that provably never left the machine, and would withhold the [Retry] that
        // is actually safe. Do not re-classify with classifyWriteFailure here: its `else`
        // branch would map a gate escape to Ambiguous.
        DiscussionWriteOutcome.Definite(e)
      } finally {
        // Released HERE, in the background block — not in runOnUi: the lifecycle guard can
        // discard the UI work entirely, and runOnUi may never run at all; releasing there
        // would leak the key forever and permanently block this target from further writes.
        InFlightWriteGuard.release(key)
      }
      scheduleTerminal(key, body, startEpoch, outcome, write)
    }
  }

  /**
   * Hands the terminal to the UI thread, containing anything the **scheduling call itself** throws.
   *
   * In production [runOnUi] is `currentDisplay.asyncExec { … }`
   * (`actions/DiscussionActionSupport.kt`), and scheduling onto a disposed SWT `Display` throws
   * `SWTException` / `IllegalStateException` from the *scheduling* call — outside the terminal body,
   * so the terminal's own handling can never see it. Left uncontained, that throwable escapes this
   * coroutine into the shared Koin [kotlinx.coroutines.CoroutineScope], which is built as
   * `CoroutineScope(Dispatchers.IO)` (`utils/WorkspaceModule.kt:22`) — a plain `Job`, **not** a
   * `SupervisorJob`. One uncaught child exception therefore cancels that scope permanently for the
   * rest of the session, taking down every other consumer with it: sidebar fetches, CI commands,
   * job-log loading. That blast radius, not the lost dialog, is what this catch exists for — **do
   * not "simplify" it away.**
   *
   * Cancellation is deliberately rethrown: structured concurrency depends on it propagating, and
   * swallowing it would hide a cancelled coroutine from its caller (the same rule as the write
   * call above).
   *
   * Containment is safe here precisely because the in-flight guard was already released in the
   * background block's `finally`, which runs before this call: swallowing a scheduling failure
   * cannot leak a key and cannot block this target from further writes.
   */
  private fun scheduleTerminal(
    key: DiscussionWriteKey,
    body: String,
    startEpoch: Long,
    outcome: DiscussionWriteOutcome,
    write: (String, Long) -> DiscussionWriteOutcome,
  ) {
    try {
      runOnUi {
        // Lifecycle guard only. NO freshness/isLatest guard here — see the class KDoc.
        if (!registryActive() || registryEpoch() != startEpoch) return@runOnUi
        applyTerminal(key, body, outcome, write)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      // Label + exceptionType only, and wrapped: a failing logger must never derail anything —
      // the same rule already applied to the escaped-throwable log above and in
      // `auditedDiscussionWrite`.
      runCatching {
        log("discussionWrite outcome=uiSchedulingFailed exceptionType=${e.javaClass.simpleName}")
      }
    }
  }

  /** UI-thread terminal: exactly one of the five branches, per the design §9.2 table. */
  private fun applyTerminal(
    key: DiscussionWriteKey,
    body: String,
    outcome: DiscussionWriteOutcome,
    write: (String, Long) -> DiscussionWriteOutcome,
  ) {
    when (outcome) {
      DiscussionWriteOutcome.Success -> reload { }
      is DiscussionWriteOutcome.Definite ->
        if (body.isEmpty()) {
          notify(DEFINITE_MESSAGE)
        } else {
          promptRetry(DEFINITE_MESSAGE, body) { newBody -> relaunch(key, newBody, write) }
        }
      is DiscussionWriteOutcome.Ambiguous -> applyAmbiguous(key, body, write)
      DiscussionWriteOutcome.GateRejected -> {
        notify(CONNECTION_CHANGED_MESSAGE)
        if (body.isNotEmpty()) promptCopyText(CONNECTION_CHANGED_MESSAGE, body)
      }
      DiscussionWriteOutcome.Aborted -> Unit // pre-send lifecycle rejection: no UI at all
    }
  }

  /**
   * The anti-duplicate-post core: reload first, and only an [LoadOutcome.Applied] reload — the
   * refreshed thread is provably on screen — may offer `[Send again]`. Every other reload result
   * (and a callback that is never invoked) leaves the user without the current state, so the
   * text is only preserved, never re-sendable from here.
   */
  private fun applyAmbiguous(
    key: DiscussionWriteKey,
    body: String,
    write: (String, Long) -> DiscussionWriteOutcome,
  ) {
    reload { loadOutcome ->
      if (loadOutcome is LoadOutcome.Applied) {
        if (body.isEmpty()) {
          notify(AMBIGUOUS_APPLIED_MESSAGE)
        } else {
          promptSendAgain(AMBIGUOUS_APPLIED_MESSAGE, body) { newBody -> relaunch(key, newBody, write) }
        }
      } else {
        if (body.isEmpty()) {
          notify(AMBIGUOUS_UNCONFIRMED_MESSAGE)
        } else {
          promptCopyText(AMBIGUOUS_UNCONFIRMED_MESSAGE, body)
        }
      }
    }
  }

  /**
   * Re-entry from `[Retry]` / `[Send again]`: the same flow with the (possibly edited) new body.
   * The guard is re-acquired (never skipped on a second attempt), and `startEpoch` is re-frozen
   * from [registryEpoch] at this moment — the click happens in a new UI turn, possibly after a
   * stop→restart, and reusing the original epoch would get the new attempt's terminal discarded.
   *
   * The freshly frozen epoch reaches the send itself because [launch] passes it to [write] as an
   * argument; nothing here (and nothing in a handler) may capture an epoch instead.
   */
  private fun relaunch(
    key: DiscussionWriteKey,
    newBody: String,
    write: (String, Long) -> DiscussionWriteOutcome,
  ) {
    launch(key, newBody, registryEpoch(), write)
  }

  companion object {
    const val ALREADY_IN_PROGRESS_MESSAGE = "Another write for this item is already in progress."
    const val CONNECTION_CHANGED_MESSAGE = "GitLab connection changed. Nothing was sent."
    const val DEFINITE_MESSAGE = "GitLab rejected this comment. You can try again."
    const val AMBIGUOUS_APPLIED_MESSAGE =
      "The result could not be confirmed. The thread has been reloaded. " +
        "Send again only if your comment is not shown above."
    const val AMBIGUOUS_UNCONFIRMED_MESSAGE =
      "The result could not be confirmed and the latest state could not be loaded. Check in GitLab."
  }
}
