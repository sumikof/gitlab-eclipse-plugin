package com.gitlab.eclipse.views.sidebar.queries

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * Owns the sidebar's queries job (design §8, F1). SWT-free so its contract is testable headless.
 *
 * The job is launched directly on the shared [scope] — a sibling of the view's `fetchJob`, never its child — so
 * cancelling or replacing it can never cancel the current-branch fetch or settle the CI-04 ticket that depends on it.
 * [launch] cancels the previous job first; the `isActive` handed to the block is the new job's own, so a superseded
 * run stops paging as well as applying. The body may throw anything — an Error included — without harming the
 * scope: it is caught and logged through [log] with the exception class only (design §11). After [dispose] nothing
 * launches. Thread-safe (guarded by a lock), though the view calls it on the UI thread only.
 */
class QueriesJobLifecycle(private val scope: CoroutineScope, private val log: (String) -> Unit = {}) {
  private val lock = Any()
  private var job: Job? = null
  private var disposed = false

  /** Cancels the running job and launches [block] in a new one; null (nothing launched) once disposed. */
  fun launch(block: suspend (isActive: () -> Boolean) -> Unit): Job? =
    synchronized(lock) {
      if (disposed) return null
      job?.cancel()
      scope.launch {
        val self = coroutineContext.job
        try {
          block { self.isActive }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Throwable) {
          // Throwable, not Exception: an Error escaping into the shared scope would cancel every sibling job.
          runCatching { log("Sidebar queries refresh failed: exceptionType=${e.javaClass.name}") }
        }
      }.also { job = it }
    }

  fun cancel() {
    synchronized(lock) { job?.cancel() }
  }

  fun dispose() {
    synchronized(lock) {
      disposed = true
      job?.cancel()
      job = null
    }
  }
}
