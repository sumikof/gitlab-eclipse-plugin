package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.utils.logger
import java.nio.channels.FileChannel
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Waits up to [timeout] for the exclusive install lock on [lockFile] in [dataRoot], as
 * [LanguageServerExtraction.install] does before it extracts, and reports the wait to [progress] as
 * [InstallPhase.WAITING_FOR_LOCK]. Every outcome is logged here.
 *
 * Returns [LockAttempt.Acquired], or [LockAttempt.Unavailable] when locking does not work on this file
 * system (the install goes on without the lock and skips the sweep); null when the install has to give
 * up: timed out, interrupted, or abandoned because the plugin is stopping.
 */
internal fun acquireInstallLock(
  lockFile: Path,
  dataRoot: Path,
  timeout: Duration,
  interval: Duration,
  open: (Path) -> FileChannel,
  shouldAbandon: () -> Boolean,
  progress: InstallProgressSession,
): LockAttempt? {
  val logger = logger<LanguageServerExtraction>()
  // The wait phase is reported only once the lock is found held, and ended however the poll ends.
  var waited = false
  val attempt = try {
    pollExclusiveLock(lockFile, timeout, interval, open, shouldAbandon) {
      logger.info("Waiting for another Eclipse instance to finish installing the GitLab Language Server in $dataRoot.")
      waited = true
      progress.begin(InstallPhase.WAITING_FOR_LOCK)
    }
  } finally {
    if (waited) progress.end(InstallPhase.WAITING_FOR_LOCK)
  }
  return when (attempt) {
    is LockAttempt.Acquired -> attempt
    is LockAttempt.Unavailable -> {
      // As 9.3.0 did, which never locked: install anyway, but never sweep without the lock.
      logger.warn(
        "The language server install lock $lockFile is unavailable on this file system " +
          "(${attempt.cause.javaClass.simpleName}: ${attempt.cause.message}); installing without it " +
          "and skipping cleanup."
      )
      attempt
    }
    LockAttempt.Held -> {
      logger.error("Timed out after $timeout waiting for the language server install lock in $dataRoot.")
      null
    }
    LockAttempt.Interrupted -> {
      logger.error("Interrupted while waiting for the language server install lock in $dataRoot.")
      null
    }
    LockAttempt.Abandoned -> {
      // The plugin is stopping: not a failure, and the next start installs again.
      logger.info("Stopped waiting for the language server install lock: the plugin is stopping.")
      null
    }
  }
}
