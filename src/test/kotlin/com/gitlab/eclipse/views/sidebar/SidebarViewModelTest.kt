package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.api.model.GitLabJob
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.api.model.GitLabPipeline
import com.gitlab.eclipse.mergerequests.CurrentBranchInfo
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
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

  fun pipeline(id: Long, status: String? = "success", webUrl: String? = "https://gitlab.example.com/p/-/pipelines/$id") =
    GitLabPipeline(id = id, status = status, webUrl = webUrl)
  fun job(
    id: Long,
    name: String? = "job$id",
    status: String? = "success",
    stage: String? = "build",
    allowFailure: Boolean? = null,
    webUrl: String? = "https://gitlab.example.com/p/-/jobs/$id",
  ) = GitLabJob(id = id, name = name, status = status, stage = stage, webUrl = webUrl, allowFailure = allowFailure)

  "buildPipelineNode: jobs sorted by id ascending, grouped by stage first-appearance order (retry has larger id)" {
    // sorted by id: 1(build), 2(test), 5(build,retry) -> stage order [build, test]; build group = [1, 5]
    val jobs = listOf(
      job(5, stage = "build"),
      job(1, stage = "build"),
      job(2, stage = "test"),
    )
    val node = SidebarViewModel().buildPipelineNode(pipeline(1), Result.success(jobs))
    node.children shouldHaveSize 2
    val stages = node.children.map { it as StageNode }
    stages[0].label shouldBe "build"
    stages[1].label shouldBe "test"
    stages[0].children.map { (it as JobNode).job.id } shouldBe listOf(1L, 5L)
    stages[1].children.map { (it as JobNode).job.id } shouldBe listOf(2L)
  }

  "buildPipelineNode: single stage" {
    val jobs = listOf(job(1, stage = "test"), job(2, stage = "test"))
    val node = SidebarViewModel().buildPipelineNode(pipeline(1), Result.success(jobs))
    node.children shouldHaveSize 1
    (node.children[0] as StageNode).label shouldBe "test"
    node.children[0].children shouldHaveSize 2
  }

  "buildPipelineNode: multiple stages preserve first-appearance order" {
    val jobs = listOf(job(3, stage = "deploy"), job(1, stage = "build"), job(2, stage = "test"))
    val node = SidebarViewModel().buildPipelineNode(pipeline(1), Result.success(jobs))
    node.children.map { (it as StageNode).label } shouldBe listOf("build", "test", "deploy")
  }

  "buildPipelineNode: empty jobs -> empty children" {
    val node = SidebarViewModel().buildPipelineNode(pipeline(1), Result.success(emptyList()))
    node.children shouldHaveSize 0
  }

  "buildPipelineNode: failed jobsResult keeps the pipeline row and shows a single failure message" {
    val node = SidebarViewModel().buildPipelineNode(pipeline(1), Result.failure(RuntimeException("boom")))
    node.children shouldHaveSize 1
    val child = node.children[0]
    (child is MessageNode) shouldBe true
    child.label shouldBe "Failed to load jobs"
  }

  "buildPipelineNode: null stage grouped under (no stage)" {
    val node = SidebarViewModel().buildPipelineNode(pipeline(1), Result.success(listOf(job(1, stage = null))))
    node.children shouldHaveSize 1
    (node.children[0] as StageNode).label shouldBe "(no stage)"
  }

  "JobNode label: status display name, missing name -> (job)" {
    val n = JobNode(job(1, name = null, status = "running"))
    n.label shouldBe "(job) · Running"
  }

  "JobNode label: failed + allowFailure -> Failed (allowed to fail)" {
    val n = JobNode(job(1, name = "flaky", status = "failed", allowFailure = true))
    n.label shouldBe "flaky · Failed (allowed to fail)"
    n.children shouldHaveSize 0
    n.activationUrl shouldBe "https://gitlab.example.com/p/-/jobs/1"
  }

  "PipelineNode label and activationUrl" {
    val node = SidebarViewModel().buildPipelineNode(pipeline(7, status = "running"), Result.success(emptyList()))
    node.label shouldBe "Pipeline #7 · Running"
    node.activationUrl shouldBe "https://gitlab.example.com/p/-/pipelines/7"
  }

  "PipelineNode activationUrl is null when pipeline webUrl is null" {
    val node = SidebarViewModel().buildPipelineNode(pipeline(7, webUrl = null), Result.success(emptyList()))
    node.activationUrl.shouldBeNull()
  }

  "buildCurrentBranchSection(input): NoRepository shows Select a repository" {
    val node = vm.buildCurrentBranchSection(NoRepository)
    (node is CurrentBranchSectionNode) shouldBe true
    node.children shouldHaveSize 1
    (node.children[0] as MessageNode).label shouldBe "Select a repository"
  }

  "buildCurrentBranchSection(input): Resolved with both null shows two Loading… placeholders" {
    val node = vm.buildCurrentBranchSection(Resolved(mr = null, pipeline = null))
    node.children shouldHaveSize 2
    (node.children[0] as MessageNode).label shouldBe "Loading…"
    (node.children[1] as MessageNode).label shouldBe "Loading…"
  }

  "buildCurrentBranchSection(input): mr success no-MR + pipeline success null -> no pipeline row, MR shown" {
    val node = vm.buildCurrentBranchSection(
      Resolved(
        mr = Result.success(CurrentBranchInfo(null, emptyList())),
        pipeline = Result.success(null),
      ),
    )
    node.children shouldHaveSize 1
    (node.children[0] as MessageNode).label shouldBe "No merge request found"
  }

  "buildCurrentBranchSection(input): pipeline failure 403 -> access-denied message, MR side unaffected" {
    val theMr = mr(2, "g/p!2")
    val node = vm.buildCurrentBranchSection(
      Resolved(
        mr = Result.success(CurrentBranchInfo(theMr, emptyList())),
        pipeline = Result.failure(GitLabApiException(403, "forbidden")),
      ),
    )
    node.children shouldHaveSize 3
    (node.children[0] as MessageNode).label shouldBe "Unable to load pipeline"
    (node.children[1] is MergeRequestNode) shouldBe true
    (node.children[2] as MessageNode).label shouldBe "No closing issue found"
  }

  "buildCurrentBranchSection(input): pipeline failure 500 -> generic load-failed message" {
    val node = vm.buildCurrentBranchSection(
      Resolved(
        mr = Result.success(CurrentBranchInfo(null, emptyList())),
        pipeline = Result.failure(GitLabApiException(500, "boom")),
      ),
    )
    node.children shouldHaveSize 2
    (node.children[0] as MessageNode).label shouldBe "Failed to load — see the Error Log."
    (node.children[1] as MessageNode).label shouldBe "No merge request found"
  }

  "buildCurrentBranchSection(input): mr still loading + pipeline resolved shows pipeline node and MR Loading…" {
    val node = vm.buildCurrentBranchSection(
      Resolved(
        mr = null,
        pipeline = Result.success(PipelineSnapshot(pipeline(9), Result.success(emptyList()))),
      ),
    )
    node.children shouldHaveSize 2
    (node.children[0] is PipelineNode) shouldBe true
    (node.children[1] as MessageNode).label shouldBe "Loading…"
  }
})
