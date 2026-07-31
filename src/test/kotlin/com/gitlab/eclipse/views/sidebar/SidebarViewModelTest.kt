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

private const val SRC_URL = "https://gitlab.example.com"
private const val SRC_FP = "fp0"

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

  fun pipeline(
    id: Long,
    status: String? = "success",
    webUrl: String? = "https://gitlab.example.com/p/-/pipelines/$id",
    projectId: Long? = null,
  ) = GitLabPipeline(id = id, status = status, webUrl = webUrl, projectId = projectId)
  fun job(
    id: Long,
    name: String? = "job$id",
    status: String? = "success",
    stage: String? = "build",
    allowFailure: Boolean? = null,
    webUrl: String? = "https://gitlab.example.com/p/-/jobs/$id",
  ) = GitLabJob(id = id, name = name, status = status, stage = stage, webUrl = webUrl, allowFailure = allowFailure)
  fun buildNode(jobsResult: Result<List<GitLabJob>>, pipeline: GitLabPipeline = pipeline(1)) =
    vm.buildPipelineNode(pipeline, jobsResult, SRC_URL, SRC_FP)

  "buildPipelineNode: jobs sorted by id ascending, grouped by stage first-appearance order (retry has larger id)" {
    // sorted by id: 1(build), 2(test), 5(build,retry) -> stage order [build, test]; build group = [1, 5]
    val jobs = listOf(
      job(5, stage = "build"),
      job(1, stage = "build"),
      job(2, stage = "test"),
    )
    val node = buildNode(Result.success(jobs))
    node.children shouldHaveSize 2
    val stages = node.children.map { it as StageNode }
    stages[0].label shouldBe "build"
    stages[1].label shouldBe "test"
    stages[0].children.map { (it as JobNode).job.id } shouldBe listOf(1L, 5L)
    stages[1].children.map { (it as JobNode).job.id } shouldBe listOf(2L)
  }

  "buildPipelineNode: single stage" {
    val jobs = listOf(job(1, stage = "test"), job(2, stage = "test"))
    val node = buildNode(Result.success(jobs))
    node.children shouldHaveSize 1
    (node.children[0] as StageNode).label shouldBe "test"
    node.children[0].children shouldHaveSize 2
  }

  "buildPipelineNode: multiple stages preserve first-appearance order" {
    val jobs = listOf(job(3, stage = "deploy"), job(1, stage = "build"), job(2, stage = "test"))
    val node = buildNode(Result.success(jobs))
    node.children.map { (it as StageNode).label } shouldBe listOf("build", "test", "deploy")
  }

  "buildPipelineNode: empty jobs -> empty children" {
    val node = buildNode(Result.success(emptyList()))
    node.children shouldHaveSize 0
  }

  "buildPipelineNode: failed jobsResult keeps the pipeline row and shows a single failure message" {
    val node = buildNode(Result.failure(RuntimeException("boom")))
    node.children shouldHaveSize 1
    val child = node.children[0]
    (child is MessageNode) shouldBe true
    child.label shouldBe "Failed to load jobs"
  }

  "buildPipelineNode: null stage grouped under (no stage)" {
    val node = buildNode(Result.success(listOf(job(1, stage = null))))
    node.children shouldHaveSize 1
    (node.children[0] as StageNode).label shouldBe "(no stage)"
  }

  "JobNode label: status display name, missing name -> (job)" {
    val n = JobNode(job(1, name = null, status = "running"), projectId = null, SRC_URL, SRC_FP)
    n.label shouldBe "(job) · Running"
  }

  "JobNode label: failed + allowFailure -> Failed (allowed to fail)" {
    val n = JobNode(job(1, name = "flaky", status = "failed", allowFailure = true), projectId = null, SRC_URL, SRC_FP)
    n.label shouldBe "flaky · Failed (allowed to fail)"
    n.children shouldHaveSize 0
    n.activationUrl shouldBe "https://gitlab.example.com/p/-/jobs/1"
  }

  "PipelineNode label and activationUrl" {
    val node = buildNode(Result.success(emptyList()), pipeline(7, status = "running"))
    node.label shouldBe "Pipeline #7 · Running"
    node.activationUrl shouldBe "https://gitlab.example.com/p/-/pipelines/7"
  }

  "PipelineNode activationUrl is null when pipeline webUrl is null" {
    val node = buildNode(Result.success(emptyList()), pipeline(7, webUrl = null))
    node.activationUrl.shouldBeNull()
  }

  "canRetry: a retryable-status job (failed/success/canceled) sets canRetry, not canCancel" {
    listOf("failed", "success", "canceled").forEach { status ->
      val node = buildNode(Result.success(listOf(job(1, status = status, stage = "s"))))
      node.canRetry shouldBe true
      node.canCancel shouldBe false
    }
  }

  "canCancel: a cancellable-status job (running/pending) sets canCancel, not canRetry" {
    listOf("running", "pending").forEach { status ->
      val node = buildNode(Result.success(listOf(job(1, status = status, stage = "s"))))
      node.canCancel shouldBe true
      node.canRetry shouldBe false
    }
  }

  "canRetry+canCancel: a failed and a running job together set both" {
    val node = buildNode(Result.success(listOf(job(1, status = "failed"), job(2, status = "running"))))
    node.canRetry shouldBe true
    node.canCancel shouldBe true
  }

  "canRetry/canCancel: only skipped/unknown-status jobs set neither" {
    val node = buildNode(Result.success(listOf(job(1, status = "skipped"), job(2, status = "no_such_status"))))
    node.canRetry shouldBe false
    node.canCancel shouldBe false
  }

  "canRetry: failed with allowFailure=true is still retryable" {
    val node = buildNode(Result.success(listOf(job(1, status = "failed", allowFailure = true))))
    node.canRetry shouldBe true
  }

  "canRetry/canCancel: empty jobs -> both false" {
    val node = buildNode(Result.success(emptyList()))
    node.canRetry shouldBe false
    node.canCancel shouldBe false
  }

  "canRetry/canCancel: failed jobsResult -> both false, failure message child kept" {
    val node = buildNode(Result.failure(RuntimeException("boom")))
    node.canRetry shouldBe false
    node.canCancel shouldBe false
    (node.children[0] as MessageNode).label shouldBe "Failed to load jobs"
  }

  "projectId carry: PipelineNode and every JobNode carry the pipeline's projectId" {
    val node = buildNode(
      Result.success(listOf(job(1, stage = "build"), job(2, stage = "test"))),
      pipeline(1, projectId = 42L),
    )
    node.pipelineId shouldBe 1L
    node.projectId shouldBe 42L
    val jobNodes = node.children.flatMap { it.children }.map { it as JobNode }
    jobNodes shouldHaveSize 2
    jobNodes.forEach { it.projectId shouldBe 42L }
  }

  "projectId carry: null pipeline projectId stays null on PipelineNode and JobNodes" {
    val node = buildNode(Result.success(listOf(job(1))), pipeline(1, projectId = null))
    node.projectId.shouldBeNull()
    (node.children[0].children[0] as JobNode).projectId.shouldBeNull()
  }

  "source tag carry: PipelineNode and every JobNode carry the tags passed to buildPipelineNode" {
    val node = vm.buildPipelineNode(
      pipeline(1, projectId = 7L),
      Result.success(listOf(job(1, stage = "build"), job(2, stage = "test"))),
      "https://other.example.org",
      "fp9",
    )
    node.sourceInstanceUrl shouldBe "https://other.example.org"
    node.sourceAuthFingerprint shouldBe "fp9"
    val jobNodes = node.children.flatMap { it.children }.map { it as JobNode }
    jobNodes shouldHaveSize 2
    jobNodes.forEach {
      it.sourceInstanceUrl shouldBe "https://other.example.org"
      it.sourceAuthFingerprint shouldBe "fp9"
    }
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
        pipeline = Result.success(PipelineSnapshot(pipeline(9), Result.success(emptyList()), SRC_URL, SRC_FP)),
      ),
    )
    node.children shouldHaveSize 2
    (node.children[0] is PipelineNode) shouldBe true
    (node.children[1] as MessageNode).label shouldBe "Loading…"
  }

  "buildCurrentBranchSection(input): snapshot source tags are forwarded to the PipelineNode" {
    val jobs = listOf(job(1, status = "failed"))
    val node = vm.buildCurrentBranchSection(
      Resolved(
        mr = null,
        pipeline = Result.success(PipelineSnapshot(pipeline(1), Result.success(jobs), "https://x", "fpX")),
      ),
    )
    val pipelineNode = node.children[0] as PipelineNode
    pipelineNode.sourceInstanceUrl shouldBe "https://x"
    pipelineNode.sourceAuthFingerprint shouldBe "fpX"
  }
})
