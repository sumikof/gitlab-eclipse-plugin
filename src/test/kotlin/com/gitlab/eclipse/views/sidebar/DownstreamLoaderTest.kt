package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.BridgeService
import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.JobService
import com.gitlab.eclipse.api.model.GitLabBridge
import com.gitlab.eclipse.api.model.GitLabJob
import com.gitlab.eclipse.api.model.GitLabPipeline
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.util.Collections
import java.util.IdentityHashMap

private const val URL = "https://gitlab.example.com"
private const val FP = "fp0"
private val SNAPSHOT = ConnectionSnapshot(URL, "tok", FP, 1L)

/**
 * CI-06 §4.6 (Task 5): [DownstreamLoader] driven through its seams — no Display. `uiDispatch` queues
 * the block (like `asyncExec`) and [Harness.drainUi] runs the queue; `launchOffUi` launches on the
 * test scope, which runs only when the scheduler advances. The tree port records refreshes and
 * answers expansion from a set.
 */
class DownstreamLoaderTest : StringSpec({
  fun job(id: Long, name: String = "job$id", status: String = "success", stage: String = "test") =
    GitLabJob(id = id, name = name, status = status, stage = stage)

  fun ds(id: Long, depth: Int = 1, ancestors: Set<Long> = setOf(100L), projectId: Long? = 42L, fp: String = FP) =
    DownstreamPipelineNode("trigger$id", downstream(id, projectId = projectId), depth, ancestors, URL, fp, 10L)

  fun section(vararg nodes: SidebarNode): List<SidebarNode> =
    listOf(
      CurrentBranchSectionNode(
        listOf(
          PipelineNode(
            GitLabPipeline(id = 100L, status = "running", projectId = 10L),
            listOf(DownstreamPipelinesNode(100L, nodes.filterIsInstance<DownstreamPipelineNode>())),
            canRetry = false,
            canCancel = false,
            sourceInstanceUrl = URL,
            sourceAuthFingerprint = FP,
          ),
        ),
      ),
    )

  class Harness(scope: CoroutineScope) {
    val ui = ArrayDeque<() -> Unit>()
    val logs = mutableListOf<String>()
    var disposed = false
    val expanded: MutableSet<SidebarNode> = Collections.newSetFromMap(IdentityHashMap())
    var roots: List<SidebarNode> = emptyList()
    val refreshed = mutableListOf<Pair<DownstreamPipelineNode, List<SidebarNode>?>>()
    var connection: ConnectionSnapshot? = SNAPSHOT
    var pins = 0
    val jobCalls = mutableListOf<Pair<String, Long>>()
    val bridgeCalls = mutableListOf<Pair<String, Long>>()
    var jobsAnswer: (Long) -> List<GitLabJob> = { listOf(job(it * 10)) }
    var bridgesAnswer: (Long) -> List<GitLabBridge> = { emptyList() }

    val source = object : DownstreamSource {
      override fun pinConnection(instanceUrl: String, authFingerprint: String): ConnectionSnapshot? {
        pins++
        return connection?.takeIf { it.instanceUrl == instanceUrl && it.authFingerprint == authFingerprint }
      }

      override fun jobs(projectId: String, pipelineId: Long, isActive: () -> Boolean, connection: ConnectionSnapshot) =
        jobsAnswer(pipelineId).also { jobCalls += projectId to pipelineId }

      override fun bridges(projectId: String, pipelineId: Long, isActive: () -> Boolean, connection: ConnectionSnapshot) =
        bridgesAnswer(pipelineId).also { bridgeCalls += projectId to pipelineId }
    }

    val tree = object : DownstreamTree {
      override fun roots(): List<SidebarNode> = roots
      override fun isDisposed(): Boolean = disposed
      override fun isExpanded(node: SidebarNode): Boolean = node in expanded
      override fun refreshSubtree(node: DownstreamPipelineNode, oldChildren: List<SidebarNode>?) {
        refreshed += node to oldChildren
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

  fun TestScope.harness() = Harness(this)

  /** Runs the IO side and the UI queue until both are idle (a UI block may launch more IO, and vice versa). */
  fun TestScope.settle(h: Harness) {
    do {
      h.drainUi()
      testScheduler.advanceUntilIdle()
    } while (h.ui.isNotEmpty())
  }

  /** Loads [node] (expanded, in the tree) to LOADED with the harness's current answers. */
  fun TestScope.loadToLoaded(h: Harness, node: DownstreamPipelineNode) {
    h.expanded += node
    h.loader.load(node)
    settle(h)
    node.loadState shouldBe DownstreamLoadState.LOADED
  }

  // --- initial load ---

  "expand: NOT_LOADED → LOADING with a new generation, then jobs + bridges on the downstream's project → LOADED" {
    runTest {
      val h = harness()
      val node = ds(205)
      h.roots = section(node)

      h.loader.load(node)

      node.loadState shouldBe DownstreamLoadState.LOADING
      node.loadGeneration shouldBe 1L
      settle(h)
      h.jobCalls shouldContainExactly listOf("42" to 205L)
      h.bridgeCalls shouldContainExactly listOf("42" to 205L)
      node.loadState shouldBe DownstreamLoadState.LOADED
      shape(node.children) shouldBe listOf("test" to listOf("job2050"))
      node.shownPayload shouldBe DownstreamPayload(Result.success(listOf(job(2050))), Result.success(emptyList()))
      h.refreshed.single().first shouldBeSameInstanceAs node
      h.logs.shouldBeEmpty()
    }
  }

  "expand: a node that is not NOT_LOADED (LOADING / LOADED / FAILED) is not loaded again" {
    runTest {
      val h = harness()
      val node = ds(205)
      h.roots = section(node)
      h.loader.load(node)
      h.loader.load(node)
      settle(h)
      h.loader.load(node)
      settle(h)

      h.jobCalls shouldContainExactly listOf("42" to 205L)
      node.loadGeneration shouldBe 1L
    }
  }

  "expand: a non-expandable node (no project / cycle / too deep) never fetches" {
    runTest {
      val h = harness()
      val nodes = listOf(
        ds(205, projectId = null),
        ds(206, ancestors = setOf(100L, 206L)),
        ds(207, depth = MAX_DOWNSTREAM_DEPTH + 1),
      )
      h.roots = section(*nodes.toTypedArray())

      nodes.forEach(h.loader::load)
      settle(h)

      h.pins shouldBe 0
      h.jobCalls.shouldBeEmpty()
      nodes.forEach {
        it.loadState shouldBe DownstreamLoadState.NOT_LOADED
        it.children.shouldBeEmpty()
      }
    }
  }

  "a stale generation is dropped" {
    runTest {
      val h = harness()
      val node = ds(205)
      h.roots = section(node)
      h.loader.load(node)
      testScheduler.advanceUntilIdle()

      node.loadGeneration++ // abandoned meanwhile (a rebuild or cancelAll)
      h.drainUi()

      node.loadedChildren.shouldBeNull()
      node.shownPayload.shouldBeNull()
      h.refreshed.shouldBeEmpty()
    }
  }

  "a detached node (no longer reachable from the viewer input) is dropped" {
    runTest {
      val h = harness()
      val node = ds(205)
      h.roots = section(node)
      h.loader.load(node)
      testScheduler.advanceUntilIdle()

      h.roots = section(ds(205)) // a rebuild replaced it with another instance
      h.drainUi()

      node.loadedChildren.shouldBeNull()
      node.loadState shouldBe DownstreamLoadState.LOADING
      h.refreshed.shouldBeEmpty()
    }
  }

  "a disposed view applies nothing" {
    runTest {
      val h = harness()
      val node = ds(205)
      h.roots = section(node)
      h.loader.load(node)
      testScheduler.advanceUntilIdle()

      h.disposed = true
      h.drainUi()

      node.loadedChildren.shouldBeNull()
      h.refreshed.shouldBeEmpty()
    }
  }

  "initial load without a confirmed connection: the message row, no fetch" {
    runTest {
      val h = harness()
      val node = ds(205, fp = "fp-other")
      h.roots = section(node)

      h.loader.load(node)
      settle(h)

      h.pins shouldBe 1
      h.jobCalls.shouldBeEmpty()
      h.bridgeCalls.shouldBeEmpty()
      node.loadState shouldBe DownstreamLoadState.FAILED
      (node.children.single() as MessageNode).label shouldBe "Could not confirm the connection. Refresh the view."
      node.shownPayload.shouldBeNull()
      h.refreshed.single().first shouldBeSameInstanceAs node
    }
  }

  "a pin that throws is logged by class only and shown as an unconfirmed connection" {
    runTest {
      val h = Harness(this)
      val throwing = object : DownstreamSource by h.source {
        override fun pinConnection(instanceUrl: String, authFingerprint: String): ConnectionSnapshot? =
          error("secret https://gitlab.example.com/token")
      }
      val loader = DownstreamLoader(
        launchOffUi = { b -> launch { b() } },
        uiDispatch = { h.ui.addLast(it) },
        source = throwing,
        tree = h.tree,
        buildChildren = SidebarViewModel()::buildDownstreamChildren,
        warnLog = { h.logs += it },
      )
      val node = ds(205)
      h.roots = section(node)

      loader.load(node)
      settle(h)

      (node.children.single() as MessageNode).label shouldBe "Could not confirm the connection. Refresh the view."
      h.logs shouldContainExactly listOf("Downstream pipeline load failed: java.lang.IllegalStateException")
    }
  }

  "jobs and bridges both empty → \"No jobs\" (partial permission answers [] rather than 403)" {
    runTest {
      val h = harness()
      h.jobsAnswer = { emptyList() }
      val node = ds(205)
      h.roots = section(node)

      h.loader.load(node)
      settle(h)

      node.loadState shouldBe DownstreamLoadState.LOADED
      (node.children.single() as MessageNode).label shouldBe "No jobs"
    }
  }

  "a 403 on jobs → unavailable row, logged with the exception class and status only" {
    runTest {
      val h = harness()
      h.jobsAnswer = { throw GitLabApiException(403, "body with trigger205 and https://gitlab.example.com/x") }
      val node = ds(205)
      h.roots = section(node)

      h.loader.load(node)
      settle(h)

      (node.children.single() as MessageNode).label shouldBe "Downstream pipeline unavailable"
      h.logs shouldContainExactly listOf("Downstream pipeline load failed: com.gitlab.eclipse.api.GitLabApiException (HTTP 403)")
    }
  }

  "a nested downstream in the loaded children is a lazy node of its own, one level deeper" {
    runTest {
      val h = harness()
      h.bridgesAnswer = { if (it == 205L) listOf(bridge(9, name = "nested", downstream = downstream(300))) else emptyList() }
      val node = ds(205)
      h.roots = section(node)

      h.loader.load(node)
      settle(h)

      val nested = (node.children.last() as DownstreamPipelinesNode).children.single()
      nested.depth shouldBe 2
      nested.ancestorPipelineIds shouldBe setOf(100L, 205L)
      nested.loadState shouldBe DownstreamLoadState.NOT_LOADED
      h.roots = section(node)
      h.loader.load(nested)
      settle(h)
      nested.loadState shouldBe DownstreamLoadState.LOADED
      h.jobCalls shouldContainExactly listOf("42" to 205L, "42" to 300L)
    }
  }

  "loadAll loads only NOT_LOADED downstream nodes among the given elements" {
    runTest {
      val h = harness()
      val a = ds(205)
      val b = ds(206).apply { loadState = DownstreamLoadState.LOADED }
      h.roots = section(a, b)

      h.loader.loadAll(listOf(MessageNode("x"), a, b))
      settle(h)

      h.jobCalls shouldContainExactly listOf("42" to 205L)
    }
  }

  "cancelAll cancels an in-flight load: nothing is fetched, the node is NOT_LOADED with a new generation" {
    runTest {
      val h = harness()
      val node = ds(205)
      h.roots = section(node)
      h.loader.load(node)

      h.loader.cancelAll()
      settle(h)

      h.pins shouldBe 0
      node.loadState shouldBe DownstreamLoadState.NOT_LOADED
      node.loadGeneration shouldBe 2L
      node.loadedChildren.shouldBeNull()
    }
  }

  "transfer: an abandoned LOADING node's job is cancelled; a LOADED one moves to the rebuilt node" {
    runTest {
      val h = harness()
      val loading = ds(205)
      val loaded = ds(206)
      h.roots = section(loading, loaded)
      loadToLoaded(h, loaded)
      h.loader.load(loading)
      val freshLoading = ds(205)
      val freshLoaded = ds(206)
      val newRoots = section(freshLoading, freshLoaded)

      h.loader.transfer(h.roots, newRoots)
      h.roots = newRoots
      settle(h)

      h.jobCalls shouldContainExactly listOf("42" to 206L) // the abandoned load never ran
      freshLoaded.loadState shouldBe DownstreamLoadState.LOADED
      freshLoading.loadState shouldBe DownstreamLoadState.NOT_LOADED
    }
  }

  // --- refresh sweep ---

  "sweep gate: rejected or disposed → nothing happens" {
    runTest {
      val h = harness()
      val node = ds(205)
      h.roots = section(node)
      loadToLoaded(h, node)
      val children = node.loadedChildren
      h.expanded.clear()

      h.loader.requestSweep { false }
      settle(h)
      h.disposed = true
      h.loader.requestSweep { true }
      settle(h)

      node.loadedChildren shouldBeSameInstanceAs children
      node.loadState shouldBe DownstreamLoadState.LOADED
    }
  }

  "sweep: a collapsed LOADED node is reset to NOT_LOADED without fetching" {
    runTest {
      val h = harness()
      val node = ds(205)
      h.roots = section(node)
      loadToLoaded(h, node)
      h.expanded.clear()
      h.refreshed.clear()

      h.loader.requestSweep { true }
      settle(h)

      node.loadState shouldBe DownstreamLoadState.NOT_LOADED
      node.loadedChildren.shouldBeNull()
      node.shownPayload.shouldBeNull()
      node.loadGeneration shouldBe 2L
      h.jobCalls shouldContainExactly listOf("42" to 205L)
      h.refreshed.single() shouldBe (node to null)
    }
  }

  "sweep: an expanded node whose re-fetch is structurally equal is not replaced" {
    runTest {
      val h = harness()
      val node = ds(205)
      h.roots = section(node)
      loadToLoaded(h, node)
      val children = node.loadedChildren
      h.refreshed.clear()

      h.loader.requestSweep { true }
      settle(h)

      h.jobCalls shouldContainExactly listOf("42" to 205L, "42" to 205L)
      node.loadedChildren shouldBeSameInstanceAs children
      h.refreshed.shouldBeEmpty()
    }
  }

  "sweep: a changed payload replaces the children, carrying a LOADED grandchild's state, then refreshes with the old children" {
    runTest {
      val h = harness()
      h.bridgesAnswer = { if (it == 205L) listOf(bridge(9, downstream = downstream(300))) else emptyList() }
      val node = ds(205)
      h.roots = section(node)
      loadToLoaded(h, node)
      val oldChildren = node.loadedChildren!!
      val nested = (oldChildren.last() as DownstreamPipelinesNode).children.single()
      loadToLoaded(h, nested)
      val nestedChildren = nested.loadedChildren
      h.refreshed.clear()
      h.jobsAnswer = { if (it == 205L) listOf(job(2050, status = "failed")) else listOf(job(it * 10)) }

      h.loader.requestSweep { true }
      settle(h)

      node.loadedChildren shouldBe node.children
      (node.loadedChildren === oldChildren) shouldBe false
      shape(node.children) shouldBe listOf("build" to listOf("bridge9"), "test" to listOf("job2050"))
      (node.children[1].children.single() as JobNode).job.status shouldBe "failed"
      val freshNested = (node.children.last() as DownstreamPipelinesNode).children.single()
      (freshNested === nested) shouldBe false
      freshNested.loadState shouldBe DownstreamLoadState.LOADED
      freshNested.loadedChildren shouldBeSameInstanceAs nestedChildren
      h.refreshed.first() shouldBe (node to oldChildren)
    }
  }

  "sweep: without a confirmed connection the expanded nodes are left as they are" {
    runTest {
      val h = harness()
      val node = ds(205)
      h.roots = section(node)
      loadToLoaded(h, node)
      val children = node.loadedChildren
      h.connection = null
      h.jobsAnswer = { listOf(job(1, status = "failed")) }

      h.loader.requestSweep { true }
      settle(h)

      node.loadState shouldBe DownstreamLoadState.LOADED
      node.loadedChildren shouldBeSameInstanceAs children
      h.jobCalls shouldContainExactly listOf("42" to 205L)
    }
  }

  "sweep: one connection pin per sweep for every expanded node on the same connection" {
    runTest {
      val h = harness()
      val a = ds(205)
      val b = ds(206)
      h.roots = section(a, b)
      loadToLoaded(h, a)
      loadToLoaded(h, b)
      h.pins = 0

      h.loader.requestSweep { true }
      settle(h)

      h.pins shouldBe 1
      h.jobCalls.drop(2) shouldContainExactly listOf("42" to 205L, "42" to 206L)
    }
  }

  "sweep: LOADING and NOT_LOADED nodes are not swept" {
    runTest {
      val h = harness()
      val loading = ds(205).apply { loadState = DownstreamLoadState.LOADING }
      val idle = ds(206)
      h.roots = section(loading, idle)
      h.expanded += listOf(loading, idle)

      h.loader.requestSweep { true }
      settle(h)

      h.pins shouldBe 0
      h.jobCalls.shouldBeEmpty()
      loading.loadState shouldBe DownstreamLoadState.LOADING
    }
  }

  "cancelSweep drops a sweep result already queued for the UI thread" {
    runTest {
      val h = harness()
      val node = ds(205)
      h.roots = section(node)
      loadToLoaded(h, node)
      val children = node.loadedChildren
      h.jobsAnswer = { listOf(job(1, status = "failed")) }

      h.loader.requestSweep { true }
      h.drainUi()
      testScheduler.advanceUntilIdle()
      h.loader.cancelSweep()
      h.drainUi()

      node.loadedChildren shouldBeSameInstanceAs children
    }
  }

  "sweep failures are logged only at the start of a streak" {
    runTest {
      val h = harness()
      val node = ds(205)
      h.roots = section(node)
      loadToLoaded(h, node)
      h.jobsAnswer = { throw GitLabApiException(500, "body") }

      h.loader.requestSweep { true }
      settle(h)
      h.loader.requestSweep { true }
      settle(h)
      h.logs shouldContainExactly listOf("Downstream pipeline load failed: com.gitlab.eclipse.api.GitLabApiException (HTTP 500)")

      h.jobsAnswer = { listOf(job(2050)) }
      h.loader.requestSweep { true }
      settle(h)
      h.jobsAnswer = { throw IllegalStateException("x") }
      h.loader.requestSweep { true }
      settle(h)

      h.logs shouldContainExactly listOf(
        "Downstream pipeline load failed: com.gitlab.eclipse.api.GitLabApiException (HTTP 500)",
        "Downstream pipeline load failed: java.lang.IllegalStateException",
      )
    }
  }

  // --- production source ---

  "ServiceDownstreamSource: jobs with include_retried, bridges, both pinned; the pin is the write gate" {
    val apiClient = mockk<GitLabApiClient>()
    val jobService = mockk<JobService>()
    val bridgeService = mockk<BridgeService>()
    every { apiClient.captureConnectionIf(any()) } answers {
      val accept = firstArg<(String) -> Boolean>()
      if (accept(URL)) SNAPSHOT else null
    }
    every { jobService.getJobsForPipeline(any(), any(), any(), any(), any()) } returns listOf(job(1))
    every { bridgeService.getBridgesForPipeline(any(), any(), any(), any()) } returns emptyList()
    val source = ServiceDownstreamSource(apiClient, jobService, bridgeService)
    val active = { true }

    source.pinConnection("$URL/", FP) shouldBe SNAPSHOT
    source.pinConnection(URL, "fp-other").shouldBeNull()
    source.pinConnection("https://other.example.com", FP).shouldBeNull()
    source.jobs("42", 205L, active, SNAPSHOT) shouldBe listOf(job(1))
    source.bridges("42", 205L, active, SNAPSHOT).shouldBeEmpty()

    verify { jobService.getJobsForPipeline("42", 205L, active, SNAPSHOT, includeRetried = true) }
    verify { bridgeService.getBridgesForPipeline("42", 205L, active, SNAPSHOT) }
  }
})
