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
   * False once the plugin activation is stopped; read by the UI runnables (design §7.3). Written
   * only by [onDeactivate] (the stop hook, on the UI thread via a syncExec marshaled off the OSGi
   * stop thread) and [onActivate] (the start hook, likewise) -- hence @Volatile.
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
   * UI thread only (the start hook calls this via `display.syncExec`). Clears every latest
   * generation recorded before this activation, so an in-flight trace fetch launched pre-stop can
   * never reflect into a freshly (re)activated UI: its generation is no longer [latest] for its
   * key, so [isLatest]/[shouldAct] fail it unconditionally. Also restores [active], which the stop
   * hook cleared -- without this, a stop->start cycle in the same class loader would leave every
   * [shouldAct] false forever and "Display Log" silently dead.
   *
   * Never touches [counter]: the monotonic sequence is preserved across activations, so a
   * post-activation generation can never collide with a pre-stop one (no ABA).
   */
  fun onActivate() {
    latest.clear()
    active = true
  }

  /**
   * Clears [active] (and nothing else -- [latest] and [counter] are untouched). Must be called ON
   * the UI thread: the stop hook runs on an OSGi thread and therefore marshals this through
   * `display.syncExec` (`GitLabEclipseStartup.stop`'s UI turn). Flipping the flag on the UI thread
   * is what totally orders the deactivation against every UI runnable's gate-check-then-act on
   * [shouldAct] (reflect) and [active] (notify); a bare off-thread write would leave a torn window
   * in which a runnable passes its gate and then acts after deactivation. [active] stays @Volatile
   * so that a display-less shutdown, where the syncExec is skipped, still cannot publish a torn
   * value. That display-less stop leaves [active] true, which is inert: without a live display no
   * reflect/notify runnable can run, and the command handlers are unregistered with the bundle.
   */
  fun onDeactivate() {
    active = false
  }

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
