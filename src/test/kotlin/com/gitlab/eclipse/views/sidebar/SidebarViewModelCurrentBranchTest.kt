package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.mergerequests.CurrentBranchInfo
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * MR-side rendering of the "For current branch" section, exercised through the
 * [CurrentBranchSectionInput] overload with a settled-empty pipeline (`Result.success(null)`
 * contributes zero pipeline children by contract), which is exactly how the Task-9 view and
 * the coordinator's MR-side memo call it. Assertions unchanged from the pre-Task-9
 * `Result`-based overload these tests originally targeted.
 */
class SidebarViewModelCurrentBranchTest : StringSpec({
  val vm = SidebarViewModel()
  fun issue(iid: Long, full: String?) =
    GitLabIssue(iid, iid, "t$iid", "u$iid", "opened", full?.let { GitLabIssue.Reference(it) })
  fun mr(iid: Long, full: String?) =
    GitLabMergeRequest(
      iid,
      iid,
      "m$iid",
      1,
      "mu$iid",
      "opened",
      references = full?.let { GitLabMergeRequest.Reference(it) },
    )
  fun section(mrResult: Result<CurrentBranchInfo>) =
    vm.buildCurrentBranchSection(Resolved(mr = mrResult, pipeline = Result.success(null)))

  "failure produces a CurrentBranchSectionNode with a single message node" {
    val node = section(Result.failure(RuntimeException("boom")))
    (node is CurrentBranchSectionNode) shouldBe true
    node.label shouldBe "For current branch"
    node.children shouldHaveSize 1
    (node.children[0] is MessageNode) shouldBe true
    (node.children[0] as MessageNode).label shouldBe "Failed to load — see the Error Log."
  }

  "success with no mr shows 'No merge request found'" {
    val node = section(Result.success(CurrentBranchInfo(null, emptyList())))
    node.children shouldHaveSize 1
    (node.children[0] as MessageNode).label shouldBe "No merge request found"
  }

  "success with mr and closing issues produces MergeRequestNode then IssueNodes" {
    val theMr = mr(2, "g/p!2")
    val issues = listOf(issue(1, "g/p#1"), issue(3, "g/p#3"))
    val node = section(Result.success(CurrentBranchInfo(theMr, issues)))
    node.children shouldHaveSize 3
    (node.children[0] is MergeRequestNode) shouldBe true
    (node.children[1] is IssueNode) shouldBe true
    (node.children[2] is IssueNode) shouldBe true
  }

  "success with mr and no closing issues shows 'No closing issue found'" {
    val theMr = mr(2, "g/p!2")
    val node = section(Result.success(CurrentBranchInfo(theMr, emptyList())))
    node.children shouldHaveSize 2
    (node.children[0] is MergeRequestNode) shouldBe true
    (node.children[1] as MessageNode).label shouldBe "No closing issue found"
  }
})
