package com.gitlab.eclipse.mergerequests

import java.util.concurrent.ConcurrentHashMap

/**
 * Serializes mutating git operations per repository, keyed by a normalized `gitDir` string.
 *
 * While a [withRepo] block for a given key is running, a second [withRepo] call on the same
 * key is rejected (returns `null`) rather than blocking or queuing. Once the running block
 * completes (normally or by exception), the key becomes reacquirable. Different keys run
 * fully independently and concurrently.
 *
 * This is a pure in-process concurrency primitive: no filesystem/process locking, no DI,
 * no JGit. It is declared now so PR-2 (push) and PR-3 (checkout) can depend on it.
 */
class GitOperationGuard {
  private val inProgress = ConcurrentHashMap<String, Boolean>()

  fun <T> withRepo(gitDir: String, block: () -> T): T? {
    val acquired = inProgress.putIfAbsent(gitDir, true) == null
    if (!acquired) {
      return null
    }
    return try {
      block()
    } finally {
      inProgress.remove(gitDir)
    }
  }
}
