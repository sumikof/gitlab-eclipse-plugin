package com.gitlab.eclipse.lsp

import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** The thread that installs and spawns the bundled language server (#124), off the activator's thread. */
internal const val LANGUAGE_SERVER_START_THREAD_NAME = "gitlab-ls-start"

private const val IDLE_SECONDS = 30L

/**
 * One daemon thread, created on demand and gone after [IDLE_SECONDS] idle, so the executor never
 * needs closing: the provider reuses it across stop and restart. Never shut down with
 * `shutdownNow`: an install must not be interrupted.
 */
internal fun newLanguageServerStartExecutor(): ExecutorService =
  ThreadPoolExecutor(1, 1, IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
    Thread(task, LANGUAGE_SERVER_START_THREAD_NAME).apply { isDaemon = true }
  }.apply { allowCoreThreadTimeOut(true) }
