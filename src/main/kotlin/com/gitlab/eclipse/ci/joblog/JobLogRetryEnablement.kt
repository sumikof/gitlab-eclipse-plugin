package com.gitlab.eclipse.ci.joblog

import com.gitlab.eclipse.utils.logger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tells the log view's Retry command that some session's Retry state changed (L4): the command
 * announces a change of its enablement itself (`fireHandlerChanged`), since nothing the handler
 * service re-evaluates on fires when a job finishes. Holds no value; [JobLogSession.canRetry] is
 * the state. A failing listener is logged by exception class and does not stop the others.
 */
internal object JobLogRetryEnablement {
  private val logger by lazy { logger<JobLogRetryEnablement>() }
  private val listeners = CopyOnWriteArrayList<() -> Unit>()

  fun addListener(listener: () -> Unit) {
    listeners += listener
  }

  fun removeListener(listener: () -> Unit) {
    listeners.remove(listener)
  }

  /** UI thread. */
  fun changed() {
    listeners.forEach { listener ->
      runCatching(listener).onFailure {
        logger.error("Job log Retry listener failed: exceptionType=${it.javaClass.name}")
      }
    }
  }
}
