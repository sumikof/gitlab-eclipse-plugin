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
 *    restarted while the request was being prepared. [epoch] still has exactly ONE writer,
 *    [onActivate], and that write happens on the UI thread, so a volatile read is sufficient and
 *    no lock is needed -- do not add a lock or an `AtomicLong`. ([onDeactivate] writes only
 *    [active], never [epoch].) [counter] and [latest] remain UI-thread-
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
   * UI thread reads shared with the CI-lint registry -- hence @Volatile. Written by exactly one
   * method, [onActivate], and only on the UI thread; [onDeactivate] writes [active] alone.
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

  /**
   * UI thread only (the sidebar's full refresh calls it where its own caches are cleared). Drops
   * every recorded latest generation and NOTHING else: [counter] keeps its monotonic sequence
   * (no generation number is ever reused), and [active]/[epoch] are untouched. A full refresh
   * rebuilds the tree with new [com.gitlab.eclipse.views.sidebar.DiscussionsSectionNode]s (new
   * nodeIds), so the old keys could only accumulate — and any load still in flight for a
   * detached pre-refresh node must not touch the rebuilt tree: with its entry cleared,
   * [isLatest] fails and it reports Superseded, which is exactly right.
   */
  fun clearLatest() {
    latest.clear()
  }

  /**
   * Clears [active] (and nothing else -- [epoch] is untouched). Must be called ON the UI thread:
   * the stop hook runs on an OSGi thread and therefore marshals this through `display.syncExec`
   * (`GitLabEclipseStartup.stop` -> `shutdownJobLog`). Flipping the flag on the UI thread is what
   * totally orders the deactivation against every UI runnable's check-then-act on [shouldAct]; a
   * bare off-thread write would leave a torn window in which a runnable passes its gate and then
   * acts after deactivation. [active] stays @Volatile so that a display-less shutdown, where the
   * syncExec is skipped, still cannot publish a torn value.
   */
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
