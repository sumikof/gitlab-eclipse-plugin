package com.gitlab.eclipse.lsp.lifecycle

import com.gitlab.eclipse.utils.logger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Holds the Language Server lifecycle state. Every mutator returns true only when the transition
 * was applied; stale generations, a closed store or an illegal phase yield false and no callback.
 * Listeners run on the mutating thread after the store's monitor is released.
 */
class LanguageServerLifecycleStore {
  private val lock = Any()
  private val listeners = CopyOnWriteArrayList<LanguageServerLifecycleListener>()

  @Volatile
  private var state = LanguageServerLifecycleState.INITIAL

  @Volatile
  private var closed = false

  val isClosed: Boolean get() = closed

  fun snapshot(): LanguageServerLifecycleState = state

  fun beginAttempt(origin: StartOrigin): Long {
    var transition: Pair<LanguageServerLifecycleState, LanguageServerLifecycleState>? = null
    synchronized(lock) {
      if (!closed) {
        val old = state
        val new = LanguageServerLifecycleState(
          phase = LanguageServerPhase.STARTING,
          generation = old.generation + 1,
          origin = origin,
          failure = null,
        )
        state = new
        transition = old to new
      }
    }
    return transition?.let { (old, new) ->
      notify(old, new)
      new.generation
    } ?: -1L
  }

  fun markRunning(generation: Long): Boolean = mutate(generation, setOf(LanguageServerPhase.STARTING)) {
    it.copy(phase = LanguageServerPhase.RUNNING, failure = null)
  }

  fun markFailed(generation: Long, kind: LanguageServerFailureKind): Boolean = mutate(
    generation,
    setOf(LanguageServerPhase.STARTING, LanguageServerPhase.RUNNING),
  ) { it.copy(phase = LanguageServerPhase.FAILED, failure = kind) }

  fun exitObserved(generation: Long): Boolean = mutate(
    generation,
    setOf(LanguageServerPhase.STARTING, LanguageServerPhase.RUNNING),
  ) {
    val kind = if (it.phase == LanguageServerPhase.STARTING) {
      LanguageServerFailureKind.EXITED_DURING_STARTUP
    } else {
      LanguageServerFailureKind.CRASHED
    }
    it.copy(phase = LanguageServerPhase.FAILED, failure = kind)
  }

  fun markStopped(generation: Long): Boolean = mutate(generation, LanguageServerPhase.entries.toSet()) {
    it.copy(phase = LanguageServerPhase.STOPPED, origin = null, failure = null)
  }

  fun close() {
    synchronized(lock) {
      closed = true
      listeners.clear()
    }
  }

  fun addListener(listener: LanguageServerLifecycleListener): AutoCloseable {
    listeners.add(listener)
    return AutoCloseable { listeners.remove(listener) }
  }

  private fun mutate(
    generation: Long,
    allowed: Set<LanguageServerPhase>,
    transform: (LanguageServerLifecycleState) -> LanguageServerLifecycleState,
  ): Boolean {
    val old: LanguageServerLifecycleState
    val new: LanguageServerLifecycleState
    synchronized(lock) {
      old = state
      if (closed || generation != old.generation || old.phase !in allowed) return false
      new = transform(old)
      state = new
    }
    notify(old, new)
    return true
  }

  private fun notify(old: LanguageServerLifecycleState, new: LanguageServerLifecycleState) {
    for (listener in listeners) {
      try {
        listener.onTransition(old, new)
      } catch (e: Exception) {
        runCatching {
          logger<LanguageServerLifecycleStore>().warn("Lifecycle listener failed (${e.javaClass.simpleName}).")
        }
      }
    }
  }
}
