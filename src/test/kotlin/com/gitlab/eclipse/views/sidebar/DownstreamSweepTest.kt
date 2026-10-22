package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.GitLabApiException
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.test.runTest

/**
 * CI-06 §4.6 R1-1 / R1-2 / R2-4 (Task 5): the refresh sweep of [DownstreamLoader] — gates, reset of collapsed
 * nodes, re-fetch of expanded ones (nested included, post-order), equality of payloads, cancellation and
 * failure-streak logging. Driven through [DownstreamHarness]; no Display.
 */
class DownstreamSweepTest : StringSpec({
  // --- refresh sweep ---

  "sweep gate: rejected or disposed → nothing happens" {
    runTest {
      val h = harness()
      val node = dsNode(205)
      h.roots = dsSection(node)
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
      val node = dsNode(205)
      h.roots = dsSection(node)
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
      val node = dsNode(205)
      h.roots = dsSection(node)
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
      val node = dsNode(205)
      h.roots = dsSection(node)
      loadToLoaded(h, node)
      val oldChildren = node.loadedChildren!!
      val nested = (oldChildren.last() as DownstreamPipelinesNode).children.single()
      loadToLoaded(h, nested)
      val nestedChildren = nested.loadedChildren
      h.refreshed.clear()
      h.jobsAnswer = { if (it == 205L) listOf(dsJob(2050, status = "failed")) else listOf(dsJob(it * 10)) }

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
      val node = dsNode(205)
      h.roots = dsSection(node)
      loadToLoaded(h, node)
      val children = node.loadedChildren
      h.connection = null
      h.jobsAnswer = { listOf(dsJob(1, status = "failed")) }

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
      val a = dsNode(205)
      val b = dsNode(206)
      h.roots = dsSection(a, b)
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
      val loading = dsNode(205).apply { loadState = DownstreamLoadState.LOADING }
      val idle = dsNode(206)
      h.roots = dsSection(loading, idle)
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
      val node = dsNode(205)
      h.roots = dsSection(node)
      loadToLoaded(h, node)
      val children = node.loadedChildren
      h.jobsAnswer = { listOf(dsJob(1, status = "failed")) }

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
      val node = dsNode(205)
      h.roots = dsSection(node)
      loadToLoaded(h, node)
      h.jobsAnswer = { throw GitLabApiException(500, "body") }

      h.loader.requestSweep { true }
      settle(h)
      h.loader.requestSweep { true }
      settle(h)
      h.logs shouldContainExactly listOf("Downstream pipeline load failed: com.gitlab.eclipse.api.GitLabApiException (HTTP 500)")

      h.jobsAnswer = { listOf(dsJob(2050)) }
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

  "d2: a sweep result for a node replaced meanwhile (transfer) is dropped" {
    runTest {
      val h = harness()
      val node = dsNode(205)
      h.roots = dsSection(node)
      loadToLoaded(h, node)
      val children = node.loadedChildren
      h.jobsAnswer = { listOf(dsJob(1, status = "failed")) }
      h.loader.requestSweep { true }
      h.drainUi()
      testScheduler.advanceUntilIdle()

      val fresh = dsNode(205)
      h.loader.transfer(h.roots, dsSection(fresh))
      h.roots = dsSection(fresh)
      h.refreshed.clear()
      h.drainUi()

      h.refreshed.shouldBeEmpty()
      fresh.loadedChildren shouldBeSameInstanceAs children
    }
  }

  "d2: a sweep result for a node whose generation moved on is dropped" {
    runTest {
      val h = harness()
      val node = dsNode(205)
      h.roots = dsSection(node)
      loadToLoaded(h, node)
      val children = node.loadedChildren
      h.jobsAnswer = { listOf(dsJob(1, status = "failed")) }
      h.loader.requestSweep { true }
      h.drainUi()
      testScheduler.advanceUntilIdle()

      node.loadGeneration++
      h.refreshed.clear()
      h.drainUi()

      h.refreshed.shouldBeEmpty()
      node.loadedChildren shouldBeSameInstanceAs children
    }
  }

  "d2: a sweep result arriving after dispose is dropped" {
    runTest {
      val h = harness()
      val node = dsNode(205)
      h.roots = dsSection(node)
      loadToLoaded(h, node)
      val children = node.loadedChildren
      h.jobsAnswer = { listOf(dsJob(1, status = "failed")) }
      h.loader.requestSweep { true }
      h.drainUi()
      testScheduler.advanceUntilIdle()

      h.disposed = true
      h.refreshed.clear()
      h.drainUi()

      h.refreshed.shouldBeEmpty()
      node.loadedChildren shouldBeSameInstanceAs children
    }
  }

  "m2 (R1-8): a node that keeps failing the same way is not rebuilt on every sweep" {
    runTest {
      val h = harness()
      val node = dsNode(205)
      h.roots = dsSection(node)
      loadToLoaded(h, node)
      h.jobsAnswer = { throw GitLabApiException(500, "body") }
      h.loader.requestSweep { true }
      settle(h)
      val failedChildren = node.loadedChildren
      h.refreshed.clear()

      h.loader.requestSweep { true }
      settle(h)

      h.jobCalls.size shouldBe 3
      node.loadedChildren shouldBeSameInstanceAs failedChildren
      h.refreshed.shouldBeEmpty()
    }
  }

  "m2 (R1-8): a different failure (another status) is a change and is rebuilt" {
    runTest {
      val h = harness()
      val node = dsNode(205)
      h.roots = dsSection(node)
      loadToLoaded(h, node)
      h.jobsAnswer = { throw GitLabApiException(500, "body") }
      h.loader.requestSweep { true }
      settle(h)
      val failedChildren = node.loadedChildren
      h.jobsAnswer = { throw GitLabApiException(403, "body") }

      h.loader.requestSweep { true }
      settle(h)

      (node.loadedChildren === failedChildren) shouldBe false
      (node.children.single() as MessageNode).label shouldBe "Downstream pipeline unavailable"
    }
  }

  "h3: the sweep refreshes an expanded nested downstream whose own jobs changed" {
    runTest {
      val h = harness()
      val node = dsNode(205)
      val nested = parentWithLoadedNested(h, node)
      h.jobsAnswer = { if (it == 300L) listOf(dsJob(3000, status = "failed")) else listOf(dsJob(it * 10)) }

      h.loader.requestSweep { true }
      settle(h)

      h.jobCalls.count { it == "42" to 300L } shouldBe 2
      (nested.children.single().children.single() as JobNode).job.status shouldBe "failed"
    }
  }

  "h3: the sweep resets a collapsed nested LOADED downstream under an expanded parent" {
    runTest {
      val h = harness()
      val node = dsNode(205)
      val nested = parentWithLoadedNested(h, node)
      h.expanded -= nested

      h.loader.requestSweep { true }
      settle(h)

      nested.loadState shouldBe DownstreamLoadState.NOT_LOADED
      nested.loadedChildren.shouldBeNull()
      node.loadState shouldBe DownstreamLoadState.LOADED
    }
  }

  "I1: parent and nested both change in one sweep — the nested node ends up showing its new payload" {
    runTest {
      val h = harness()
      val node = dsNode(205)
      parentWithLoadedNested(h, node)
      h.jobsAnswer = { listOf(dsJob(it * 10, status = "failed")) }

      h.loader.requestSweep { true }
      settle(h)

      val nestedNow = (node.children.last() as DownstreamPipelinesNode).children.single()
      (node.children.filterIsInstance<StageNode>().single { it.name == "test" }.children.single() as JobNode)
        .job.status shouldBe "failed"
      nestedNow.loadState shouldBe DownstreamLoadState.LOADED
      (nestedNow.children.single().children.single() as JobNode).job.status shouldBe "failed"
    }
  }

  "x7: after a sweep rebuild, an expanded nested node that was never loaded starts its first load" {
    runTest {
      val h = harness()
      h.bridgesAnswer = { if (it == 205L) listOf(bridge(9, downstream = downstream(300))) else emptyList() }
      val node = dsNode(205)
      h.roots = dsSection(node)
      loadToLoaded(h, node)
      h.expanded += (node.children.last() as DownstreamPipelinesNode).children.single() // expanded, NOT_LOADED
      h.jobsAnswer = { if (it == 205L) listOf(dsJob(2050, status = "failed")) else listOf(dsJob(it * 10)) }

      h.loader.requestSweep { true }
      settle(h)

      val freshNested = (node.children.last() as DownstreamPipelinesNode).children.single()
      freshNested.loadState shouldBe DownstreamLoadState.LOADED
      h.jobCalls.count { it == "42" to 300L } shouldBe 1
    }
  }

  "n3: cancelSweep stops the running sweep's fetches, not only its results" {
    runTest {
      val h = harness()
      val a = dsNode(205)
      val b = dsNode(206)
      h.roots = dsSection(a, b)
      loadToLoaded(h, a)
      loadToLoaded(h, b)
      val pins = h.pins

      h.loader.requestSweep { true }
      h.drainUi()
      h.loader.cancelSweep()
      settle(h)

      h.jobCalls.size shouldBe 2
      h.pins shouldBe pins
    }
  }

  "x10: a collapsed reset cancels the loads in flight below it" {
    runTest {
      val h = harness()
      h.bridgesAnswer = { if (it == 205L) listOf(bridge(9, downstream = downstream(300))) else emptyList() }
      val node = dsNode(205)
      h.roots = dsSection(node)
      loadToLoaded(h, node)
      val nested = (node.children.last() as DownstreamPipelinesNode).children.single()
      h.loader.load(nested) // queued, not yet running
      h.expanded.clear()

      h.loader.requestSweep { true }
      settle(h)

      nested.loadGeneration shouldBe 2L
      h.jobCalls.none { it == "42" to 300L } shouldBe true
      node.loadState shouldBe DownstreamLoadState.NOT_LOADED
    }
  }
})
