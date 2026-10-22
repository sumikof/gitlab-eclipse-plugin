package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.model.GitLabPipeline
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

private const val URL = "https://gitlab.example.com"
private const val FP = "fp0"

/**
 * CI-06 §4.6 R1-1 / R2-1 / R2-3 (Task 5): [transferDownstreamState] carries a LOADED downstream node's
 * state onto its rebuilt counterpart only when the pipeline id AND the source tags match, never
 * transfers a LOADING node (it is abandoned instead), and finds nested nodes through LOADED parents.
 */
class DownstreamTransferTest : StringSpec({
  fun ds(
    id: Long,
    url: String = URL,
    fp: String = FP,
    depth: Int = 1,
    ancestors: Set<Long> = setOf(100L),
  ) = DownstreamPipelineNode("trigger$id", downstream(id), depth, ancestors, url, fp, parentProjectId = 42L)

  fun DownstreamPipelineNode.loaded(
    children: List<SidebarNode> = listOf(StageNode("test", "Passed", emptyList())),
    gen: Long = 3,
  ) =
    apply {
      loadState = DownstreamLoadState.LOADED
      loadedChildren = children
      shownPayload = DownstreamPayload(Result.success(emptyList()), Result.success(emptyList()))
      loadGeneration = gen
    }

  fun section(vararg nodes: DownstreamPipelineNode, parent: Long = 100L): List<SidebarNode> =
    listOf(
      CurrentBranchSectionNode(
        listOf(
          PipelineNode(
            GitLabPipeline(id = parent, status = "running", projectId = 42L),
            listOf(DownstreamPipelinesNode(parent, nodes.toList())),
            canRetry = false,
            canCancel = false,
            sourceInstanceUrl = URL,
            sourceAuthFingerprint = FP,
          ),
        ),
      ),
    )

  "same pipeline id and same tags: the rebuilt node takes the old LOADED state, children as the same instances" {
    val old = ds(205).loaded()
    val oldChildren = old.loadedChildren
    val oldPayload = old.shownPayload
    val fresh = ds(205)

    transferDownstreamState(section(old), section(fresh)).shouldBeEmpty()

    fresh.loadState shouldBe DownstreamLoadState.LOADED
    fresh.loadedChildren shouldBeSameInstanceAs oldChildren
    fresh.children shouldBeSameInstanceAs oldChildren
    fresh.shownPayload shouldBeSameInstanceAs oldPayload
    fresh.loadGeneration shouldBe 3L
    // The old instance's generation moves on, so a sweep result still in flight for it is dropped.
    old.loadGeneration shouldBe 4L
  }

  "a trailing-slash difference in the instance URL is the same instance" {
    val fresh = ds(205, url = "$URL/")

    transferDownstreamState(section(ds(205).loaded()), section(fresh))

    fresh.loadState shouldBe DownstreamLoadState.LOADED
  }

  "a different auth fingerprint (account switch) transfers nothing" {
    val old = ds(205).loaded()
    val fresh = ds(205, fp = "fp1")

    transferDownstreamState(section(old), section(fresh)).shouldBeEmpty()

    fresh.loadState shouldBe DownstreamLoadState.NOT_LOADED
    fresh.loadedChildren.shouldBeNull()
    fresh.shownPayload.shouldBeNull()
    (fresh.children.single() as MessageNode).label shouldBe "Loading…"
    fresh.loadGeneration shouldBe 0L
  }

  "a different instance URL transfers nothing" {
    val fresh = ds(205, url = "https://other.example.com")

    transferDownstreamState(section(ds(205).loaded()), section(fresh))

    fresh.loadState shouldBe DownstreamLoadState.NOT_LOADED
    fresh.loadedChildren.shouldBeNull()
  }

  "a different pipeline id transfers nothing" {
    val fresh = ds(206)

    transferDownstreamState(section(ds(205).loaded()), section(fresh))

    fresh.loadState shouldBe DownstreamLoadState.NOT_LOADED
  }

  "a LOADING old node is not transferred: it is abandoned (generation bumped) and returned for cancellation" {
    val old = ds(205).apply {
      loadState = DownstreamLoadState.LOADING
      loadGeneration = 1
    }
    val fresh = ds(205)

    transferDownstreamState(section(old), section(fresh)) shouldContainExactly listOf(old)

    old.loadGeneration shouldBe 2L
    fresh.loadState shouldBe DownstreamLoadState.NOT_LOADED
    fresh.loadedChildren.shouldBeNull()
  }

  "FAILED (connection not confirmed) is not transferred either" {
    val old = ds(205).apply {
      loadState = DownstreamLoadState.FAILED
      loadedChildren = listOf(MessageNode("x"))
    }
    val fresh = ds(205)

    transferDownstreamState(section(old), section(fresh)).shouldBeEmpty()

    fresh.loadState shouldBe DownstreamLoadState.NOT_LOADED
  }

  "a node the coordinator kept (same instance in both trees) is left alone, even while LOADING" {
    val kept = ds(205).apply {
      loadState = DownstreamLoadState.LOADING
      loadGeneration = 1
    }
    val tree = section(kept)

    transferDownstreamState(tree, section(kept)).shouldBeEmpty()

    kept.loadState shouldBe DownstreamLoadState.LOADING
    kept.loadGeneration shouldBe 1L
  }

  "one old node is handed to one new node only" {
    val first = ds(205)
    val second = ds(205)

    transferDownstreamState(section(ds(205).loaded()), section(first) + section(second, parent = 101L))

    first.loadState shouldBe DownstreamLoadState.LOADED
    second.loadState shouldBe DownstreamLoadState.NOT_LOADED
  }

  "recursive: a nested node rebuilt by the sweep takes the state of its predecessor inside a LOADED parent" {
    val innerStage = StageNode("deploy", "Running", emptyList())
    val oldNested = ds(300, depth = 2, ancestors = setOf(100L, 205L)).loaded(listOf(innerStage), gen = 7)
    val parent = ds(205).loaded(listOf(DownstreamPipelinesNode(205L, listOf(oldNested))))
    val oldChildren = parent.loadedChildren!!
    // What the sweep builds from a changed payload: a new group with a fresh nested node.
    val freshNested = ds(300, depth = 2, ancestors = setOf(100L, 205L))
    val newChildren = listOf<SidebarNode>(
      StageNode("build", "Passed", emptyList()),
      DownstreamPipelinesNode(205L, listOf(freshNested)),
    )

    transferDownstreamState(oldChildren, newChildren).shouldBeEmpty()

    freshNested.loadState shouldBe DownstreamLoadState.LOADED
    freshNested.children.single() shouldBeSameInstanceAs innerStage
    freshNested.loadGeneration shouldBe 7L
  }

  "recursive through a transferred parent: its nested LOADING node stays attached and is not abandoned" {
    val nestedLoading = ds(300, depth = 2, ancestors = setOf(100L, 205L)).apply {
      loadState = DownstreamLoadState.LOADING
      loadGeneration = 1
    }
    val old = ds(205).loaded(listOf(DownstreamPipelinesNode(205L, listOf(nestedLoading))))
    val fresh = ds(205)

    transferDownstreamState(section(old), section(fresh)).shouldBeEmpty()

    fresh.loadState shouldBe DownstreamLoadState.LOADED
    nestedLoading.loadGeneration shouldBe 1L
  }

  "i6: the search descends below a LOADED downstream node — a nested LOADING load the rebuild dropped is abandoned" {
    val nestedLoading = ds(300, depth = 2, ancestors = setOf(100L, 205L)).apply {
      loadState = DownstreamLoadState.LOADING
      loadGeneration = 1
    }
    val old = ds(205).loaded(listOf(DownstreamPipelinesNode(205L, listOf(nestedLoading))))

    transferDownstreamState(section(old), section()) shouldContainExactly listOf(nestedLoading)

    nestedLoading.loadGeneration shouldBe 2L
  }

  "i6: a nested LOADED node below a downstream that was not carried over still donates to its rebuilt counterpart" {
    val innerStage = StageNode("deploy", "Running", emptyList())
    val oldNested = ds(300, depth = 2, ancestors = setOf(100L, 205L)).loaded(listOf(innerStage))
    // The old nested node is only reachable through its LOADED parent 205, which the new tree no longer has;
    val oldParent = ds(205).loaded(listOf(DownstreamPipelinesNode(205L, listOf(oldNested))))
    val freshNested = ds(300, depth = 2, ancestors = setOf(100L, 205L))
    // its rebuilt counterpart sits under another (kept-state) parent and must still find it.
    val newParent = ds(206).loaded(listOf(DownstreamPipelinesNode(206L, listOf(freshNested))))

    transferDownstreamState(section(oldParent), section(newParent))

    freshNested.loadState shouldBe DownstreamLoadState.LOADED
    freshNested.children.single() shouldBeSameInstanceAs innerStage
  }

  "nested nodes under a NOT-loaded node are not searched (a placeholder has none)" {
    downstreamNodesIn(section(ds(205))).map { it.pipelineId } shouldContainExactly listOf(205L)
  }

  "a non-expandable rebuilt node (cycle / depth) never takes state" {
    val cycle = ds(205, ancestors = setOf(100L, 205L))
    val tooDeep = ds(206, depth = MAX_DOWNSTREAM_DEPTH + 1)

    transferDownstreamState(section(ds(205).loaded(), ds(206).loaded()), section(cycle, tooDeep))

    cycle.loadState shouldBe DownstreamLoadState.NOT_LOADED
    tooDeep.loadState shouldBe DownstreamLoadState.NOT_LOADED
    cycle.children.shouldBeEmpty()
  }
})
