package com.gitlab.eclipse.ci.lint

/**
 * UI-thread-owned generation state for CI lint runs (design §8.4a), mirroring
 * [com.gitlab.eclipse.ci.joblog.JobLogGenerationRegistry]. Every invocation is numbered on the UI
 * thread; only the run holding the LATEST generation for its [CiLintKey] may reflect a result
 * into the editor or notify. There is no mutex/AtomicLong: all mutation and every read happen on
 * the single, serial UI thread, so that confinement IS the synchronization. [counter] is globally
 * monotonic and never reused, so a superseded generation can never be mistaken for a fresh one
 * (no ABA) -- in particular it is NOT reset by [onActivate], only by [resetForTest].
 *
 * Unlike the job-log registry, this one also tracks an [epoch]: each [onActivate] bumps it and
 * clears [latest], so any generation numbered before the plugin was stopped is unconditionally
 * failed by [isLatest]/[shouldAct] even if its number would otherwise still look latest. This
 * closes the window where a lint launched just before `stop()` completes just after the next
 * `start()` and would otherwise reflect a stale result into a freshly (re)activated UI.
 */
object CiLintGenerationRegistry {
  /**
   * False once the plugin activation is stopped (set by the stop hook, possibly via a syncExec
   * marshaled off the caller thread -- hence @Volatile); read by the UI runnables.
   */
  @Volatile
  var active: Boolean = true

  private var counter: Long = 0
  private var epoch: Long = 0
  private val latest = HashMap<CiLintKey, Long>()

  /**
   * UI thread only. The activation epoch captured at `execute` time and compared against in the
   * asynchronous completion callback (alongside [isLatest]/[shouldAct]).
   */
  val currentEpoch: Long get() = epoch

  /** UI thread only. Assign the next monotonic generation and record it as the latest for [key]. */
  fun nextGeneration(key: CiLintKey): Long {
    counter += 1
    latest[key] = counter
    return counter
  }

  /** UI thread only. True iff [generation] is the most recent one assigned for [key]. */
  fun isLatest(key: CiLintKey, generation: Long): Boolean = latest[key] == generation

  /**
   * UI thread only. The gate every UI reflect/notify runnable applies: still the active
   * activation AND still the latest generation for [key].
   */
  fun shouldAct(key: CiLintKey, generation: Long): Boolean = active && isLatest(key, generation)

  /**
   * UI thread only (the start hook calls this via `display.syncExec`). Clears every latest
   * generation recorded before this activation (so an in-flight lint launched pre-stop can never
   * reflect post-restart), bumps [epoch], and restores [active]. Never touches [counter]: the
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
