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
  fun bridge(id: Long, name: String? = "bridge$id", stage: String? = "build", downstream: GitLabPipeline? = null) =
    GitLabBridge(id, name, stage, "success", "https://gitlab.example.com/p/-/jobs/$id", null, downstream)
  fun downstream(id: Long, status: String? = "running", projectId: Long? = 42L, source: String? = "parent_pipeline") =
    GitLabPipeline(
      id = id,
      status = status,
      projectId = projectId,
      source = source,
      webUrl = "https://gitlab.example.com/d/-/pipelines/$id",
    )

  /** Stage names with their children's names (the label part before the status), in tree order. */
  fun shape(children: List<SidebarNode>) =
    children.filterIsInstance<StageNode>().map { stage ->
      stage.name to stage.children.map { it.label.substringBefore(" · ") }
    }

  fun stageIds(children: List<SidebarNode>) =
    children.filterIsInstance<StageNode>().map { stage ->
      stage.children.map { (it as? JobNode)?.job?.id ?: (it as BridgeNode).bridge.id }
    }

  // --- DownstreamPipelineNode ---

  fun dsNode(
    pipeline: GitLabPipeline = downstream(205),
    depth: Int = 1,
    ancestors: Set<Long> = setOf(100L),
    bridgeName: String? = "docs",
    parentProjectId: Long? = 42L,
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
    dsNode(pipeline = downstream(205, source = null, projectId = 42L)).label shouldBe
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

  "buildDownstreamChildren: bridges only (no jobs) -> bridge stage, no No-jobs row" {
    val bridgesOnly = payload(Result.success(emptyList()), Result.success(listOf(bridge(3, name = "t"))))
    vm.buildDownstreamChildren(dsNode(), bridgesOnly)
      .map { it.label } shouldBe listOf("build · Passed")
  }
})
