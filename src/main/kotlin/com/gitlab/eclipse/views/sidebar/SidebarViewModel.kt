package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.mergerequests.CurrentBranchInfo
import com.gitlab.eclipse.views.issues.configErrorMessage

private const val NO_ISSUES_MESSAGE = "No issues assigned to you."
private const val NO_MRS_MESSAGE = "No merge requests assigned to you."
private const val LOAD_FAILED_MESSAGE = "Failed to load — see the Error Log."
private const val NO_CURRENT_BRANCH_MR_MESSAGE = "No merge request found"
private const val NO_CLOSING_ISSUE_MESSAGE = "No closing issue found"

/**
 * Pure composition logic for the sidebar's two query roots ("Issues assigned to me",
 * "Merge requests assigned to me"): turns the raw fetch results into [SidebarNode]s,
 * grouping by project in [SidebarViewMode.TREE] and isolating each root's failure from
 * the other's.
 */
class SidebarViewModel {

  fun buildRoots(
    issuesResult: Result<List<GitLabIssue>>,
    mrsResult: Result<List<GitLabMergeRequest>>,
    mode: SidebarViewMode,
  ): List<SidebarNode> =
    listOf(
      QueryRootNode(
        "Issues assigned to me",
        issuesChildren(issuesResult, mode),
      ),
      QueryRootNode(
        "Merge requests assigned to me",
        mrsChildren(mrsResult, mode),
      ),
    )

  private fun issuesChildren(result: Result<List<GitLabIssue>>, mode: SidebarViewMode): List<SidebarNode> =
    result.fold(
      onSuccess = { issues -> successChildren(issues, mode, NO_ISSUES_MESSAGE, { it.references?.full }, ::IssueNode) },
      onFailure = { error -> failureChildren(error) },
    )

  private fun mrsChildren(result: Result<List<GitLabMergeRequest>>, mode: SidebarViewMode): List<SidebarNode> =
    result.fold(
      onSuccess = { mrs -> successChildren(mrs, mode, NO_MRS_MESSAGE, { it.references?.full }, ::MergeRequestNode) },
      onFailure = { error -> failureChildren(error) },
    )

  private fun <T> successChildren(
    items: List<T>,
    mode: SidebarViewMode,
    emptyMessage: String,
    fullReference: (T) -> String?,
    toNode: (T) -> SidebarNode,
  ): List<SidebarNode> {
    if (items.isEmpty()) return listOf(MessageNode(emptyMessage))
    return when (mode) {
      SidebarViewMode.LIST -> items.map(toNode)
      SidebarViewMode.TREE ->
        items
          .groupBy { projectKey(fullReference(it)) }
          .map { (project, group) -> ProjectGroupNode(project, group.map(toNode)) }
    }
  }

  private fun failureChildren(error: Throwable): List<SidebarNode> =
    listOf(MessageNode(configErrorMessage(error) ?: LOAD_FAILED_MESSAGE))

  /**
   * Builds the "For current branch" section (design doc §8.3): the open merge request for the
   * currently checked-out branch (if any) and the issues it would close.
   */
  fun buildCurrentBranchSection(result: Result<CurrentBranchInfo>): SidebarNode =
    CurrentBranchSectionNode(
      result.fold(
        onSuccess = { info -> currentBranchChildren(info) },
        onFailure = { error -> failureChildren(error) },
      ),
    )

  private fun currentBranchChildren(info: CurrentBranchInfo): List<SidebarNode> {
    val mr = info.mr ?: return listOf(MessageNode(NO_CURRENT_BRANCH_MR_MESSAGE))
    val issueNodes =
      info.closesIssues
        .map(::IssueNode)
        .ifEmpty { listOf(MessageNode(NO_CLOSING_ISSUE_MESSAGE)) }
    return listOf(MergeRequestNode(mr)) + issueNodes
  }
}

/** Groups items by project, keyed off the `namespace/path#iid` or `namespace/path!iid` reference. */
internal fun projectKey(full: String?): String =
  full?.substringBefore('#')?.substringBefore('!')?.trim()?.ifBlank { null } ?: "Unknown project"
