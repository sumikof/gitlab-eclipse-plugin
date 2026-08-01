package com.gitlab.eclipse.ci.joblog

/**
 * UI-thread-owned generation state for "Display Log" runs (design §14). Every invocation is
 * numbered on the UI thread; only the run holding the LATEST generation for its [JobLogKey] may
 * reflect a result into the editor or notify. There is no mutex/AtomicLong: all mutation and
 * every read happen on the single, serial UI thread, so that confinement IS the synchronization
 * (design §14.1). [counter] is globally monotonic and never reused, so a superseded generation
 * can never be mistaken for a fresh one (no ABA).
 */
object JobLogGenerationRegistry {
  /**
   * False once the plugin activation is stopped (set by the stop hook, possibly via a syncExec
   * marshaled off the caller thread — hence @Volatile); read by the UI runnables (design §7.3).
   */
  @Volatile
  var active: Boolean = true

  private var counter: Long = 0
  private val latest = HashMap<JobLogKey, Long>()

  /** UI thread only. Assign the next monotonic generation and record it as the latest for [key]. */
  fun nextGeneration(key: JobLogKey): Long {
    counter += 1
    latest[key] = counter
    return counter
  }

  /** UI thread only. True iff [generation] is the most recent one assigned for [key]. */
  fun isLatest(key: JobLogKey, generation: Long): Boolean = latest[key] == generation

  /**
   * UI thread only. The gate every UI reflect/notify runnable applies: still the active
   * activation AND still the latest generation for [key].
   */
  fun shouldAct(key: JobLogKey, generation: Long): Boolean = active && isLatest(key, generation)

  /**
   * Test-only: the registry is a process-wide singleton, so tests reset it between runs to keep
   * generation numbers and activation state from leaking across tests. Never called in
   * production code.
   */
  internal fun resetForTest() {
    counter = 0
    latest.clear()
    active = true
  }
}
