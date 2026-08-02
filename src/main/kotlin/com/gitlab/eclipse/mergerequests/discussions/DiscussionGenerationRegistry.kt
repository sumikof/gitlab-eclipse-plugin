package com.gitlab.eclipse.mergerequests.discussions

/**
 * UI-thread-owned generation state for discussion fetches, mirroring
 * [com.gitlab.eclipse.ci.lint.CiLintGenerationRegistry]. Every invocation is numbered on the UI
 * thread; only the fetch holding the LATEST generation for its [DiscussionKey] may write a result
 * into the sidebar tree. There is no mutex/AtomicLong for [counter]/[latest]: all mutation and
 * every read of those two happen on the single, serial UI thread, so that confinement IS the
 * synchronization. [counter] is globally monotonic and never reused, so a superseded generation
 * can never be mistaken for a fresh one (no ABA) -- in particular it is NOT reset by
 * [onActivate], only by [resetForTest].
 *
 * Like the CI-lint registry, this one also tracks an [epoch]: each [onActivate] bumps it and
 * clears [latest], so any generation numbered before the plugin was stopped is unconditionally
 * failed by [isLatest]/[shouldAct] even if its number would otherwise still look latest. This
 * closes the window where a fetch launched just before `stop()` completes just after the next
 * `start()` and would otherwise write a stale result into a freshly (re)activated sidebar.
 *
 * Two deliberate differences from [com.gitlab.eclipse.ci.lint.CiLintGenerationRegistry]:
 *
 * 1. [DiscussionKey] includes an auth fingerprint (unlike `CiLintKey`) so that switching accounts
 *    against the same instance URL cannot let a slow in-flight response from the old account
 *    compete for the same generation slot as the new account's fetch -- see [DiscussionKey]'s doc.
 * 2. [epoch] is `@Volatile` here, whereas the CI-lint registry's is a plain field. There, `epoch`
 *    is only ever read on the UI thread. Here, a later PR reads [currentEpoch] from a background
 *    thread immediately before sending a write, to verify the plugin was not stopped and
 *    restarted while the request was being prepared. Writes to [epoch] still happen only on the
 *    UI thread ([onActivate]/[onDeactivate]), so a volatile read is sufficient and no lock is
 *    needed -- do not add a lock or an `AtomicLong`. [counter] and [latest] remain UI-thread-
 *    confined and must NOT be made volatile or synchronized; that confinement is the
 *    synchronization, exactly as in the model class.
 */
object DiscussionGenerationRegistry {
  /**
   * False once the plugin activation is stopped (set by the stop hook, possibly via a syncExec
   * marshaled off the caller thread -- hence @Volatile); read by the UI runnables.
   */
  @Volatile
  var active: Boolean = true

  /**
   * Read from a background thread right before a write is sent (a later PR), in addition to the
   * UI thread reads shared with the CI-lint registry -- hence @Volatile. Written only on the UI
   * thread, by [onActivate]/[onDeactivate].
   */
  @Volatile
  private var epoch: Long = 0

  private var counter: Long = 0
  private val latest = HashMap<DiscussionKey, Long>()

  /**
   * The activation epoch captured at fetch-start time and compared against right before a
   * background thread sends its write, alongside [isLatest]/[shouldAct].
   */
  val currentEpoch: Long get() = epoch

  /** UI thread only. Assign the next monotonic generation and record it as the latest for [key]. */
  fun nextGeneration(key: DiscussionKey): Long {
    counter += 1
    latest[key] = counter
    return counter
  }

  /** UI thread only. True iff [generation] is the most recent one assigned for [key]. */
  fun isLatest(key: DiscussionKey, generation: Long): Boolean = latest[key] == generation

  /**
   * UI thread only. The gate every sidebar-write runnable applies: still the active activation
   * AND still the latest generation for [key].
   */
  fun shouldAct(key: DiscussionKey, generation: Long): Boolean = active && isLatest(key, generation)

  /**
   * UI thread only (the start hook calls this via `display.syncExec`). Clears every latest
   * generation recorded before this activation (so an in-flight fetch launched pre-stop can never
   * write post-restart), bumps [epoch], and restores [active]. Never touches [counter]: the
   * monotonic sequence is preserved across activations (no ABA).
   */
  fun onActivate() {
    latest.clear()
    epoch += 1
    active = true
  }

  /** May be called from any thread (the stop hook runs on an OSGi thread) -- @Volatile write only. */
  fun onDeactivate() {
    active = false
  }

  /**
   * Test-only: the registry is a process-wide singleton, so tests reset it between runs to keep
   * generation numbers, epoch, and activation state from leaking across tests. Never called in
   * production code.
   */
  internal fun resetForTest() {
    counter = 0
    epoch = 0
    latest.clear()
    active = true
  }
}
