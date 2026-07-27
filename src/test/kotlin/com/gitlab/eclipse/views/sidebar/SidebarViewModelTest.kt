package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class SidebarViewModelTest : StringSpec({
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

  "list mode: flat item nodes per root" {
    val roots = vm.buildRoots(
      Result.success(listOf(issue(1, "g/p#1"))),
      Result.success(listOf(mr(2, "g/p!2"))),
      SidebarViewMode.LIST,
    )
    roots shouldHaveSize 2
    roots[0].children shouldHaveSize 1
    roots[1].children shouldHaveSize 1
  }
  "tree mode: group by project" {
    val roots = vm.buildRoots(
      Result.success(listOf(issue(1, "a/x#1"), issue(2, "b/y#2"))),
      Result.success(emptyList()),
      SidebarViewMode.TREE,
    )
    roots[0].children shouldHaveSize 2 // two ProjectGroupNode
  }
  "empty success shows message node" {
    val roots = vm.buildRoots(Result.success(emptyList()), Result.success(emptyList()), SidebarViewMode.LIST)
    (roots[0].children[0] is MessageNode) shouldBe true
  }
  "one root failure does not affect the other" {
    val roots = vm.buildRoots(
      Result.failure(RuntimeException("x")),
      Result.success(listOf(mr(2, "g/p!2"))),
      SidebarViewMode.LIST,
    )
    (roots[0].children[0] is MessageNode) shouldBe true
    roots[1].children shouldHaveSize 1
  }
})
