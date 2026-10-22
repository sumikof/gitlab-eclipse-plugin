package com.gitlab.eclipse.currentbranch

import com.gitlab.eclipse.views.sidebar.CurrentBranchSectionInput

/**
 * One window's shared current-branch state at a point in time (status-bar design §4.1). [input] is `null` while
 * nothing has settled yet; otherwise it is the exact instance the section fetcher produced (CI-05 memo identity).
 * [version] increases with every applied change, [gitDir] / [workTree] describe the tracked repository
 * (`null` when none), and [refreshing] says a refresh is currently in flight.
 */
data class CurrentBranchSnapshot(
  val version: Long,
  val gitDir: String?,
  val workTree: String?,
  val input: CurrentBranchSectionInput?,
  val refreshing: Boolean,
) {
  // Paths identify the user's checkout, so they stay out of logs.
  override fun toString(): String = "CurrentBranchSnapshot(version=$version, refreshing=$refreshing)"
}
