package com.gitlab.eclipse.views.sidebar.autorefresh

import kotlinx.coroutines.Job

/**
 * What the sidebar view owns for the CI-04 auto refresh, start to dispose (design §2.4/§5): the
 * [refresher] and the running soft refresh's [Job]. [dispose] stops the refresher — removing its
 * JGit, Display and part listeners and cancelling its timers — and cancels the soft refresh, so a
 * closed view leaves nothing behind. Kept apart from the view so that contract is testable headless.
 *
 * UI thread only, except [replaceSoftJob]'s job field, which is volatile because cancellation of a
 * coroutine job is thread-safe and the view reads it while disposing.
 */
class AutoRefreshLifecycle(private val log: (String) -> Unit) {
  /** The started refresher, or null when auto refresh is off (not started, or [create] failed). */
  var refresher: CurrentBranchAutoRefresher? = null
    private set

  @Volatile private var softJob: Job? = null

  /** Creates and starts the refresher; a failure leaves auto refresh off, logged by class name only. */
  fun start(create: () -> CurrentBranchAutoRefresher) {
    refresher = try {
      create().also { it.start() }
    } catch (e: Exception) {
      runCatching { log("Current-branch auto refresh is off: ${e.javaClass.name}") }
      null
    }
  }

  /** A new soft refresh supersedes the running one. */
  fun replaceSoftJob(job: Job) {
    softJob?.cancel()
    softJob = job
  }

  /** A manual refresh supersedes the running soft refresh. */
  fun cancelSoftJob() {
    softJob?.cancel()
  }

  fun dispose() {
    refresher?.stop()
    softJob?.cancel()
  }
}
