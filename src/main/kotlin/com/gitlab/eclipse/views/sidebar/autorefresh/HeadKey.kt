package com.gitlab.eclipse.views.sidebar.autorefresh

/**
 * What a repository's HEAD points at, as far as the CI-04 auto refresh cares (design §2.2/§3): the
 * refresher re-fetches the "For current branch" section only when this changes. `toString()` names
 * the kind only, so a key that reaches a log never leaks the branch name or SHA (design §5).
 */
sealed interface HeadKey {
  data class Branch(val name: String) : HeadKey {
    override fun toString() = "Branch"
  }

  data class Detached(val sha: String) : HeadKey {
    override fun toString() = "Detached"
  }

  /** HEAD could not be read (repository closed, deleted, unborn). */
  data object Unavailable : HeadKey
}

/**
 * The repository (git dir) and HEAD a settled refresh looked at; `toString()` omits the paths. [workTree] is the
 * resolved repository's work tree as resolved (not canonicalised; status-bar design §4.1 R4), null where not
 * known. [onSingleTag]: a detached HEAD with exactly one tag pointing at it, i.e. the pipeline side targets that
 * tag (status-bar §4.5: a tag shows the pipeline item only; any other detached HEAD shows nothing). The refresher
 * compares [head] only.
 */
data class TrackedHead(
  val gitDir: String,
  val head: HeadKey,
  val workTree: String? = null,
  val onSingleTag: Boolean = false,
) {
  override fun toString() = "TrackedHead($head)"
}

/** How a refresh ended, as far as tracking is concerned (see [CurrentBranchAutoRefresher.onRefreshSettled]). */
sealed interface SettleOutcome {
  /** The fetch resolved a repository and read (or failed to read: [HeadKey.Unavailable]) its HEAD. */
  data class Looked(val head: TrackedHead) : SettleOutcome

  /** The fetch resolved no repository: the sidebar shows none. */
  data object NoRepository : SettleOutcome

  /** The fetch was cancelled, failed or never ran: it says nothing about the repository. */
  data object Unknown : SettleOutcome
}
