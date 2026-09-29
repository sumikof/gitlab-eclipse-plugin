package com.gitlab.eclipse.chat.quickchat

import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicInteger

/**
 * How many abandoned Quick Chat jobs are still running (design §15.3): sends whose ticket was
 * released (finished, closed, replaced) while their background had not returned, and background
 * `/clear` / `/reset` sends.
 *
 * An `object` — one per class loader, never in Koin and never reset on activation — so the count
 * survives a bundle stop → start in the same class loader (design §15.3, A34): the stop cancels
 * the scope, but a call that ignores interrupts keeps running and must keep counting.
 */
object QuickChatDetachedJobs {
  private val running = AtomicInteger()

  val count: Int get() = running.get()

  /**
   * Counts [job] until it completes. A job already complete is not counted; one completing during
   * this call is counted and immediately uncounted by its handler, which holds only this object.
   */
  fun track(job: Job) {
    if (job.isCompleted) return
    running.incrementAndGet()
    job.invokeOnCompletion { running.decrementAndGet() }
  }

  /** True while no new background work may start (design §9.2 step 4, §15.4). */
  fun atLimit(max: Int = QuickChatLimits.MAX_DETACHED): Boolean = count >= max
}
