package com.gitlab.eclipse.views.issues

import java.util.concurrent.atomic.AtomicLong

/** Monotonic refresh generation so out-of-order async completions can be discarded. */
class ViewRefreshState {
  private val current = AtomicLong(0)
  fun begin(): Long = current.incrementAndGet()
  fun isCurrent(generation: Long): Boolean = generation == current.get()

  /**
   * The generation in effect right now, without starting a new one — for secondary async
   * work (e.g. lazy child loads) that must be discarded once a newer full refresh applies.
   */
  fun currentGeneration(): Long = current.get()
}
