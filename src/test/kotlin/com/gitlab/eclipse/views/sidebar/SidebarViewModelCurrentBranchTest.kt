package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.mergerequests.CurrentBranchInfo
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

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

  "failure produces a CurrentBranchSectionNode with a single message node" {
    val node = vm.buildCurrentBranchSection(Result.failure(RuntimeException("boom")))
    (node is CurrentBranchSectionNode) shouldBe true
    node.label shouldBe "For current branch"
    node.children shouldHaveSize 1
    (node.children[0] is MessageNode) shouldBe true
    (node.children[0] as MessageNode).label shouldBe "Failed to load — see the Error Log."
  }

  "success with no mr shows 'No merge request found'" {
    val node = vm.buildCurrentBranchSection(Result.success(CurrentBranchInfo(null, emptyList())))
    node.children shouldHaveSize 1
    (node.children[0] as MessageNode).label shouldBe "No merge request found"
  }

  "success with mr and closing issues produces MergeRequestNode then IssueNodes" {
    val theMr = mr(2, "g/p!2")
    val issues = listOf(issue(1, "g/p#1"), issue(3, "g/p#3"))
    val node = vm.buildCurrentBranchSection(Result.success(CurrentBranchInfo(theMr, issues)))
    node.children shouldHaveSize 3
    (node.children[0] is MergeRequestNode) shouldBe true
    (node.children[1] is IssueNode) shouldBe true
    (node.children[2] is IssueNode) shouldBe true
  }

  "success with mr and no closing issues shows 'No closing issue found'" {
    val theMr = mr(2, "g/p!2")
    val node = vm.buildCurrentBranchSection(Result.success(CurrentBranchInfo(theMr, emptyList())))
    node.children shouldHaveSize 2
    (node.children[0] is MergeRequestNode) shouldBe true
    (node.children[1] as MessageNode).label shouldBe "No closing issue found"
  }
})
