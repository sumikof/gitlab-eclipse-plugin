package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicReference

/**
 * The per-activation home of every Quick Chat background job (design §8.1, §17): one Koin `single`
 * shared by all windows. Its own [SupervisorJob] scope, so one failed send never cancels another and
 * the shared plugin scope (E4) is not involved.
 *
 * [close] cancels the scope and returns at once; it never joins, since a call ignoring interrupts may
 * not return (design §15.3). The abandoned-job count lives in [QuickChatDetachedJobs], not here.
 */
class QuickChatRuntime(
  val clock: MonotonicClock = MonotonicClock.SYSTEM,
  dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
  // An exception escaping a job would otherwise reach the thread's default handler with its message.
  private val onUncaught = CoroutineExceptionHandler { _, e ->
    logger<QuickChatRuntime>().warn("Quick Chat background job failed: ${e.javaClass.name}")
  }

  val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher + onUncaught)

  override fun close() {
    scope.cancel()
  }
}

/**
 * The [QuickChatRuntime] of the current activation, so the bundle stop can close it without creating
 * one (Koin never closes an `AutoCloseable` single, and asking Koin for it would instantiate it).
 * The Koin `single` creates it through [create]; the stop calls [closeIfCreated].
 */
object QuickChatRuntimeLifecycle {
  private val created = AtomicReference<QuickChatRuntime?>()

  /** The runtime created and not yet closed, if any. */
  val current: QuickChatRuntime? get() = created.get()

  fun create(): QuickChatRuntime = QuickChatRuntime().also { created.set(it) }

  /**
   * Cancels the created runtime's scope, so no preflight, send, poll or background `/clear` keeps
   * working for a stopped bundle. Never joins (a call ignoring interrupts may not return) and never
   * resets [QuickChatDetachedJobs]. Never throws: it runs on the bundle stop path.
   */
  fun closeIfCreated() {
    try {
      created.getAndSet(null)?.close()
    } catch (e: Exception) {
      logger<QuickChatRuntimeLifecycle>().warn("Quick Chat shutdown skipped: ${e.javaClass.name}")
    }
  }
}
