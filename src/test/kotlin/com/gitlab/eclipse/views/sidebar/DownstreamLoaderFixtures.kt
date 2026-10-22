package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.model.GitLabBridge
import com.gitlab.eclipse.api.model.GitLabJob
import com.gitlab.eclipse.api.model.GitLabPipeline
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import java.util.Collections
import java.util.IdentityHashMap

// Shared harness for the CI-06 Task 5 loader tests (DownstreamLoaderTest, DownstreamSweepTest):
// [DownstreamLoader] driven through its seams — no Display. `uiDispatch` queues the block (like
// `asyncExec`) and [DownstreamHarness.drainUi] runs the queue; `launchOffUi` launches on the test
// scope, which runs only when the scheduler advances. The tree port records refreshes and answers
// expansion from a set.

internal const val DS_URL = "https://gitlab.example.com"
internal const val DS_FP = "fp0"
internal val DS_SNAPSHOT = ConnectionSnapshot(DS_URL, "tok", DS_FP, 1L)

internal fun dsJob(id: Long, name: String = "job$id", status: String = "success", stage: String = "test") =
  GitLabJob(id = id, name = name, status = status, stage = stage)

internal fun dsNode(
  id: Long,
  depth: Int = 1,
  ancestors: Set<Long> = setOf(100L),
  projectId: Long? = 42L,
  fp: String = DS_FP,
) =
  DownstreamPipelineNode("trigger$id", downstream(id, projectId = projectId), depth, ancestors, DS_URL, fp, 10L)

internal fun dsSection(vararg nodes: SidebarNode): List<SidebarNode> =
  listOf(
    CurrentBranchSectionNode(
      listOf(
        PipelineNode(
          GitLabPipeline(id = 100L, status = "running", projectId = 10L),
          listOf(DownstreamPipelinesNode(100L, nodes.filterIsInstance<DownstreamPipelineNode>())),
          canRetry = false,
          canCancel = false,
          sourceInstanceUrl = DS_URL,
          sourceAuthFingerprint = DS_FP,
        ),
      ),
    ),
  )

internal class DownstreamHarness(scope: CoroutineScope) {
  val ui = ArrayDeque<() -> Unit>()
  val logs = mutableListOf<String>()
  var disposed = false
  val expanded: MutableSet<SidebarNode> = Collections.newSetFromMap(IdentityHashMap())
  var roots: List<SidebarNode> = emptyList()
  val refreshed = mutableListOf<Pair<DownstreamPipelineNode, List<SidebarNode>?>>()
  var connection: ConnectionSnapshot? = DS_SNAPSHOT
  var pins = 0
  val jobCalls = mutableListOf<Pair<String, Long>>()
  val bridgeCalls = mutableListOf<Pair<String, Long>>()
  var jobsAnswer: (Long) -> List<GitLabJob> = { listOf(dsJob(it * 10)) }
  val jobsIsActive = mutableListOf<() -> Boolean>()
  var bridgesAnswer: (Long) -> List<GitLabBridge> = { emptyList() }

  val source = object : DownstreamSource {
    override fun pinConnection(instanceUrl: String, authFingerprint: String): ConnectionSnapshot? {
      pins++
      return connection?.takeIf { it.instanceUrl == instanceUrl && it.authFingerprint == authFingerprint }
    }

    override fun jobs(projectId: String, pipelineId: Long, isActive: () -> Boolean, connection: ConnectionSnapshot) =
      run {
        jobCalls += projectId to pipelineId
        jobsIsActive += isActive
        jobsAnswer(pipelineId)
      }

    override fun bridges(projectId: String, pipelineId: Long, isActive: () -> Boolean, connection: ConnectionSnapshot) =
      bridgesAnswer(pipelineId).also { bridgeCalls += projectId to pipelineId }
  }

  val tree = object : DownstreamTree {
    override fun roots(): List<SidebarNode> = roots
    override fun isDisposed(): Boolean = disposed
    override fun isExpanded(node: SidebarNode): Boolean = node in expanded

    // Mimics the view: after a rebuild, the expansion of the old children is carried onto the new
    // ones by stable key (remapInChildren), parents first.
    override fun refreshSubtree(node: DownstreamPipelineNode, oldChildren: List<SidebarNode>?) {
      refreshed += node to oldChildren
      if (oldChildren != null) {
        expanded.toList().mapNotNull { remapInChildren(it, oldChildren, node.children) }.forEach { expanded += it }
      }
    }
  }

  val loader = DownstreamLoader(
    launchOffUi = { block -> scope.launch { block() } },
    uiDispatch = { ui.addLast(it) },
    source = source,
    tree = tree,
    buildChildren = SidebarViewModel()::buildDownstreamChildren,
    warnLog = { logs += it },
  )

  fun drainUi() {
    while (ui.isNotEmpty()) ui.removeFirst()()
  }
}

internal fun TestScope.harness() = DownstreamHarness(this)

/** Runs the IO side and the UI queue until both are idle (a UI block may launch more IO, and vice versa). */
internal fun TestScope.settle(h: DownstreamHarness) {
  do {
    h.drainUi()
    testScheduler.advanceUntilIdle()
  } while (h.ui.isNotEmpty())
}

/** Loads [node] (expanded, in the tree) to LOADED with the harness's current answers. */
internal fun TestScope.loadToLoaded(h: DownstreamHarness, node: DownstreamPipelineNode) {
  h.expanded += node
  h.loader.load(node)
  settle(h)
  node.loadState shouldBe DownstreamLoadState.LOADED
}

/** Parent 205 loaded with a nested downstream 300 (also loaded); both expanded. Returns the nested node. */
internal fun TestScope.parentWithLoadedNested(
  h: DownstreamHarness,
  node: DownstreamPipelineNode,
): DownstreamPipelineNode {
  h.bridgesAnswer = { if (it == 205L) listOf(bridge(9, downstream = downstream(300))) else emptyList() }
  h.roots = dsSection(node)
  loadToLoaded(h, node)
  val nested = (node.children.last() as DownstreamPipelinesNode).children.single()
  loadToLoaded(h, nested)
  return nested
}
