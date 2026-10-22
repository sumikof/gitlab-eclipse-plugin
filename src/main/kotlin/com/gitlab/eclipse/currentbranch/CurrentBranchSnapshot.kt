package com.gitlab.eclipse.currentbranch

import com.gitlab.eclipse.views.sidebar.CurrentBranchSectionInput

/** What HEAD of the tracked repository points at; drives which status-bar items apply. */
enum class HeadKind { BRANCH, TAG, DETACHED, UNKNOWN }

/**
 * One window's shared current-branch state at a point in time (status-bar design §4.1). [input] is `null` while
 * nothing has settled yet; otherwise it is the exact instance the section fetcher produced (CI-05 memo identity).
 * [version] increases with every applied change, [gitDir] / [workTree] describe the tracked repository
 * (`null` when none), [refreshing] says a refresh is currently in flight, and [headKind] says what HEAD points at
 * ([HeadKind.UNKNOWN] until the state layer fills it, which behaves like a branch).
 */
data class CurrentBranchSnapshot(
  val version: Long,
  val gitDir: String?,
  val workTree: String?,
  val input: CurrentBranchSectionInput?,
  val refreshing: Boolean,
  val headKind: HeadKind = HeadKind.UNKNOWN,
) {
  // Paths identify the user's checkout, so they stay out of logs.
  override fun toString(): String = "CurrentBranchSnapshot(version=$version, refreshing=$refreshing)"
}
