package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.model.GitLabBridge
import com.gitlab.eclipse.api.model.GitLabJob
import com.gitlab.eclipse.api.model.GitLabPipeline
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private const val SRC_URL = "https://gitlab.example.com"
private const val SRC_FP = "fp0"

/**
 * CI-06 §4.6 (Task 4): [DownstreamPipelineNode]'s expandability, label and placeholder, and the pure
 * [SidebarViewModel.buildDownstreamChildren] that Task 5 calls with a fetched [DownstreamPayload].
 */
class SidebarViewModelDownstreamTest : StringSpec({
  val vm = SidebarViewModel()

  fun job(id: Long, name: String? = "job$id", status: String? = "success", stage: String? = "build") =
    GitLabJob(id = id, name = name, status = status, stage = stage)
  // --- DownstreamPipelineNode ---

  fun dsNode(
    pipeline: GitLabPipeline = downstream(205),
    depth: Int = 1,
    ancestors: Set<Long> = setOf(100L),
    bridgeName: String? = "docs",
    // Differs from the downstream's own project (42) on purpose: rows must carry the downstream's id.
    parentProjectId: Long? = 10L,
  ) = DownstreamPipelineNode(bridgeName, pipeline, depth, ancestors, SRC_URL, SRC_FP, parentProjectId)

  "DownstreamPipelineNode: expandable only with a project id, no cycle and depth ≤ MAX_DOWNSTREAM_DEPTH" {
    dsNode().expandable shouldBe true
    dsNode(depth = MAX_DOWNSTREAM_DEPTH).expandable shouldBe true
    dsNode(depth = MAX_DOWNSTREAM_DEPTH + 1).expandable shouldBe false
    dsNode(pipeline = downstream(205, projectId = null)).expandable shouldBe false
    dsNode(ancestors = setOf(100L, 205L)).expandable shouldBe false
    MAX_DOWNSTREAM_DEPTH shouldBe 10
  }

  "DownstreamPipelineNode: a leaf has no children even once loaded; expandable shows a stable Loading… until loaded" {
    val leaf = dsNode(pipeline = downstream(205, projectId = null))
    leaf.loadedChildren = listOf(MessageNode("x"))
    leaf.children shouldHaveSize 0
    leaf.activationUrl shouldBe "https://gitlab.example.com/d/-/pipelines/205"

    val node = dsNode()
    (node.children.single() as MessageNode).label shouldBe "Loading…"
    (node.children === node.children) shouldBe true
    val loaded = listOf<SidebarNode>(MessageNode("No jobs"))
    node.loadedChildren = loaded
    (node.children === loaded) shouldBe true
    node.pipelineId shouldBe 205L
    node.projectId shouldBe 42L
    node.loadGeneration shouldBe 0L
    node.shownPayload.shouldBeNull()
  }

  "DownstreamPipelineNode: kind suffix from source, else from the project ids, else none" {
    dsNode(pipeline = downstream(205, status = "success", source = "parent_pipeline")).label shouldBe
      "docs → Pipeline #205 · Passed (child)"
    dsNode(pipeline = downstream(205, source = "pipeline")).label shouldBe
      "docs → Pipeline #205 · Running (multi-project)"
    dsNode(pipeline = downstream(205, source = null, projectId = 99L)).label shouldBe
      "docs → Pipeline #205 · Running (multi-project)"
    dsNode(pipeline = downstream(205, source = null, projectId = 42L), parentProjectId = 42L).label shouldBe
      "docs → Pipeline #205 · Running (child)"
    dsNode(pipeline = downstream(205, source = null), parentProjectId = null).label shouldBe
      "docs → Pipeline #205 · Running"
    dsNode(bridgeName = null).label shouldBe "(bridge) → Pipeline #205 · Running (child)"
  }

  // --- buildDownstreamChildren (pure; Task 5 calls it with the fetched payload) ---

  fun payload(jobs: Result<List<GitLabJob>>, bridges: Result<List<GitLabBridge>> = Result.success(emptyList())) =
    DownstreamPayload(jobs, bridges)

  "buildDownstreamChildren: stages carry the downstream's project id and the parent's tags; nested group recurses" {
    val parent = dsNode()
    val children = vm.buildDownstreamChildren(
      parent,
      payload(
        Result.success(listOf(job(31, name = "t", stage = "test"))),
        Result.success(
          listOf(bridge(32, name = "grandchild", stage = "test", downstream = downstream(300, projectId = 77L))),
        ),
      ),
    )
    children.map { it::class.simpleName } shouldBe listOf("StageNode", "DownstreamPipelinesNode")
    val (jobNode, bridgeNode) = children[0].children
    (jobNode as JobNode).projectId shouldBe 42L
    jobNode.sourceInstanceUrl shouldBe SRC_URL
    jobNode.sourceAuthFingerprint shouldBe SRC_FP
    (bridgeNode as BridgeNode).projectId shouldBe 42L
    bridgeNode.sourceAuthFingerprint shouldBe SRC_FP
    val group = children[1] as DownstreamPipelinesNode
    group.parentPipelineId shouldBe 205L
    val grandchild = group.children.single()
    grandchild.depth shouldBe 2
    grandchild.ancestorPipelineIds shouldBe setOf(100L, 205L)
    grandchild.parentProjectId shouldBe 42L
    grandchild.projectId shouldBe 77L
    grandchild.sourceInstanceUrl shouldBe SRC_URL
    grandchild.sourceAuthFingerprint shouldBe SRC_FP
    grandchild.label shouldBe "grandchild → Pipeline #300 · Running (child)"
  }

  "buildDownstreamChildren: a downstream pointing back at an ancestor is a leaf (cycle)" {
    val children = vm.buildDownstreamChildren(
      dsNode(),
      payload(Result.success(emptyList()), Result.success(listOf(bridge(32, downstream = downstream(100))))),
    )
    (children.last() as DownstreamPipelinesNode).children.single().expandable shouldBe false
  }

  "buildDownstreamChildren: retried jobs keep their place (same ordering rule as the root)" {
    val children = vm.buildDownstreamChildren(
      dsNode(),
      payload(
        Result.success(
          listOf(job(1, name = "a", stage = "s1"), job(2, name = "b", stage = "s2"), job(9, name = "a", stage = "s1")),
        ),
      ),
    )
    shape(children) shouldBe listOf("s1" to listOf("a"), "s2" to listOf("b"))
    stageIds(children) shouldBe listOf(listOf(9L), listOf(2L))
  }

  "buildDownstreamChildren: jobs and bridges both empty -> No jobs (partial permission returns [])" {
    vm.buildDownstreamChildren(dsNode(), payload(Result.success(emptyList())))
      .map { it.label } shouldBe listOf("No jobs")
  }

  "buildDownstreamChildren: bridges failed -> job stages then the downstream failure message" {
    vm.buildDownstreamChildren(dsNode(), payload(Result.success(listOf(job(1))), Result.failure(RuntimeException("x"))))
      .map { it.label } shouldBe listOf("build · Passed", "Failed to load downstream pipelines")
    vm.buildDownstreamChildren(dsNode(), payload(Result.success(emptyList()), Result.failure(RuntimeException("x"))))
      .map { it.label } shouldBe listOf("Failed to load downstream pipelines")
  }

  "buildDownstreamChildren: jobs 403/404 -> unavailable, any other failure -> failed to load" {
    listOf(403, 404).forEach { code ->
      vm.buildDownstreamChildren(dsNode(), payload(Result.failure(GitLabApiException(code, "x"))))
        .map { it.label } shouldBe listOf("Downstream pipeline unavailable")
    }
    vm.buildDownstreamChildren(dsNode(), payload(Result.failure(GitLabApiException(500, "x"))))
      .map { it.label } shouldBe listOf("Failed to load downstream pipeline")
    vm.buildDownstreamChildren(dsNode(), payload(Result.failure(RuntimeException("x"))))
      .map { it.label } shouldBe listOf("Failed to load downstream pipeline")
  }

  "buildDownstreamChildren: a jobs failure other than 403/404 keeps the nested downstream group (same as the root)" {
    val withDownstream = Result.success(listOf(bridge(32, downstream = downstream(300, projectId = 77L))))
    listOf(GitLabApiException(500, "x"), RuntimeException("x")).forEach { error ->
      val children = vm.buildDownstreamChildren(dsNode(), payload(Result.failure(error), withDownstream))
      children.map { it::class.simpleName } shouldBe listOf("MessageNode", "DownstreamPipelinesNode")
      children[0].label shouldBe "Failed to load downstream pipeline"
      (children[1] as DownstreamPipelinesNode).children.single().pipelineId shouldBe 300L
    }
    val bothFailed = payload(Result.failure(RuntimeException("x")), Result.failure(RuntimeException("y")))
    vm.buildDownstreamChildren(dsNode(), bothFailed)
      .map { it.label } shouldBe listOf("Failed to load downstream pipeline", "Failed to load downstream pipelines")
  }

  "buildDownstreamChildren: a jobs 403/404 shows only the unavailable row, even with downstreams in the bridges" {
    val withDownstream = Result.success(listOf(bridge(32, downstream = downstream(300, projectId = 77L))))
    listOf(403, 404).forEach { code ->
      vm.buildDownstreamChildren(dsNode(), payload(Result.failure(GitLabApiException(code, "x")), withDownstream))
        .map { it.label } shouldBe listOf("Downstream pipeline unavailable")
    }
  }

  "buildDownstreamChildren: rows of a multi-project downstream carry the downstream's project id, not the parent's" {
    val parent = dsNode(pipeline = downstream(205, projectId = 77L, source = "pipeline"), parentProjectId = 42L)
    val children = vm.buildDownstreamChildren(
      parent,
      payload(
        Result.success(listOf(job(31, name = "t", stage = "test"))),
        Result.success(listOf(bridge(32, name = "b", stage = "test", downstream = downstream(300, projectId = 88L)))),
      ),
    )
    val rows = children.filterIsInstance<StageNode>().flatMap { it.children }
    rows.map { it::class.simpleName } shouldBe listOf("JobNode", "BridgeNode")
    (rows[0] as JobNode).projectId shouldBe 77L
    (rows[1] as BridgeNode).projectId shouldBe 77L
    (children.last() as DownstreamPipelinesNode).children.single().parentProjectId shouldBe 77L
  }

  "buildDownstreamChildren: bridges only (no jobs) -> bridge stage, no No-jobs row" {
    val bridgesOnly = payload(Result.success(emptyList()), Result.success(listOf(bridge(3, name = "t"))))
    vm.buildDownstreamChildren(dsNode(), bridgesOnly)
      .map { it.label } shouldBe listOf("build · Passed")
  }
})
