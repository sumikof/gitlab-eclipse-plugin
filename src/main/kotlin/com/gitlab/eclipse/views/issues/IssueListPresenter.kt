package com.gitlab.eclipse.views.issues

import com.gitlab.eclipse.api.model.GitLabIssue

/**
 * Applies an async fetch result to the view only if it belongs to the current
 * refresh generation and the view is not disposed. Kept free of SWT so it is unit
 * testable headless.
 */
class IssueListPresenter(
  private val state: ViewRefreshState,
  private val isDisposed: () -> Boolean,
  private val applyInput: (List<GitLabIssue>) -> Unit,
  private val showError: (Throwable) -> Unit,
) {
  fun onResult(generation: Long, result: Result<List<GitLabIssue>>) {
    if (!state.isCurrent(generation)) return
    if (isDisposed()) return
    result
      .onSuccess { applyInput(it) }
      .onFailure { showError(it) }
  }
}
