package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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
