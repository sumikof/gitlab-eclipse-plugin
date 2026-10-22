package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.BridgeService
import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.JobService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * CI-06 §4.6 (Task 5): the first load of a [DownstreamPipelineNode] — gates, messages, logs, cancellation —
 * and the production [ServiceDownstreamSource]. Driven through [DownstreamHarness]; no Display.
 */
class DownstreamLoaderTest : StringSpec({
  // --- initial load ---

  "expand: NOT_LOADED → LOADING with a new generation, then jobs + bridges on the downstream's project → LOADED" {
    runTest {
      val h = harness()
      val node = dsNode(205)
      h.roots = dsSection(node)

      h.loader.load(node)

      node.loadState shouldBe DownstreamLoadState.LOADING
      node.loadGeneration shouldBe 1L
      settle(h)
      h.jobCalls shouldContainExactly listOf("42" to 205L)
      h.bridgeCalls shouldContainExactly listOf("42" to 205L)
      node.loadState shouldBe DownstreamLoadState.LOADED
      shape(node.children) shouldBe listOf("test" to listOf("job2050"))
      node.shownPayload shouldBe DownstreamPayload(Result.success(listOf(dsJob(2050))), Result.success(emptyList()))
      h.refreshed.single().first shouldBeSameInstanceAs node
      h.logs.shouldBeEmpty()
    }
  }

  "expand: a LOADING or LOADED node is not loaded again" {
    runTest {
      val h = harness()
      val node = dsNode(205)
      h.roots = dsSection(node)
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
        dsNode(205, projectId = null),
        dsNode(206, ancestors = setOf(100L, 206L)),
        dsNode(207, depth = MAX_DOWNSTREAM_DEPTH + 1),
      )
      h.roots = dsSection(*nodes.toTypedArray())

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
      val node = dsNode(205)
      h.roots = dsSection(node)
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
      val node = dsNode(205)
      h.roots = dsSection(node)
      h.loader.load(node)
      testScheduler.advanceUntilIdle()

      h.roots = dsSection(dsNode(205)) // a rebuild replaced it with another instance
      h.drainUi()

      node.loadedChildren.shouldBeNull()
      node.loadState shouldBe DownstreamLoadState.LOADING
      h.refreshed.shouldBeEmpty()
    }
  }

  "a disposed view applies nothing" {
    runTest {
      val h = harness()
      val node = dsNode(205)
      h.roots = dsSection(node)
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
      val node = dsNode(205, fp = "fp-other")
      h.roots = dsSection(node)

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
      val h = DownstreamHarness(this)
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
      val node = dsNode(205)
      h.roots = dsSection(node)

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
      val node = dsNode(205)
      h.roots = dsSection(node)

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
      val node = dsNode(205)
      h.roots = dsSection(node)

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
      val node = dsNode(205)
      h.roots = dsSection(node)

      h.loader.load(node)
      settle(h)

      val nested = (node.children.last() as DownstreamPipelinesNode).children.single()
      nested.depth shouldBe 2
      nested.ancestorPipelineIds shouldBe setOf(100L, 205L)
      nested.loadState shouldBe DownstreamLoadState.NOT_LOADED
      h.roots = dsSection(node)
      h.loader.load(nested)
      settle(h)
      nested.loadState shouldBe DownstreamLoadState.LOADED
      h.jobCalls shouldContainExactly listOf("42" to 205L, "42" to 300L)
    }
  }

  "loadAll loads only NOT_LOADED downstream nodes among the given elements" {
    runTest {
      val h = harness()
      val a = dsNode(205)
      val b = dsNode(206).apply { loadState = DownstreamLoadState.LOADED }
      h.roots = dsSection(a, b)

      h.loader.loadAll(listOf(MessageNode("x"), a, b))
      settle(h)

      h.jobCalls shouldContainExactly listOf("42" to 205L)
    }
  }

  "cancelAll cancels an in-flight load: nothing is fetched, the node is NOT_LOADED with a new generation" {
    runTest {
      val h = harness()
      val node = dsNode(205)
      h.roots = dsSection(node)
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
      val loading = dsNode(205)
      val loaded = dsNode(206)
      h.roots = dsSection(loading, loaded)
      loadToLoaded(h, loaded)
      h.loader.load(loading)
      val freshLoading = dsNode(205)
      val freshLoaded = dsNode(206)
      val newRoots = dsSection(freshLoading, freshLoaded)

      h.loader.transfer(h.roots, newRoots)
      h.roots = newRoots
      settle(h)

      h.jobCalls shouldContainExactly listOf("42" to 206L) // the abandoned load never ran
      freshLoaded.loadState shouldBe DownstreamLoadState.LOADED
      freshLoading.loadState shouldBe DownstreamLoadState.NOT_LOADED
    }
  }

  // --- fix round 1 ---

  "x13: load() on a disposed view starts nothing" {
    runTest {
      val h = harness()
      val node = dsNode(205)
      h.roots = dsSection(node)
      h.disposed = true

      h.loader.load(node)
      settle(h)

      h.pins shouldBe 0
      node.loadState shouldBe DownstreamLoadState.NOT_LOADED
      node.loadGeneration shouldBe 0L
    }
  }

  "m5: re-expanding a FAILED node (connection not confirmed) retries the load" {
    runTest {
      val h = harness()
      val node = dsNode(205)
      h.roots = dsSection(node)
      h.connection = null
      h.loader.load(node)
      settle(h)
      node.loadState shouldBe DownstreamLoadState.FAILED

      h.connection = DS_SNAPSHOT
      h.loader.load(node)
      settle(h)

      node.loadState shouldBe DownstreamLoadState.LOADED
      shape(node.children) shouldBe listOf("test" to listOf("job2050"))
      h.jobCalls shouldContainExactly listOf("42" to 205L)
    }
  }

  "m2a / x14 / x20: cancelAll during the jobs fetch stops the load — no bridges, no log, nothing applied" {
    runTest {
      val h = harness()
      val node = dsNode(205)
      h.roots = dsSection(node)
      var activeAfterCancel: Boolean? = null
      h.jobsAnswer = {
        h.loader.cancelAll()
        activeAfterCancel = h.jobsIsActive.single()()
        listOf(dsJob(1))
      }

      h.loader.load(node)
      settle(h)

      activeAfterCancel shouldBe false
      h.bridgeCalls.shouldBeEmpty()
      h.logs.shouldBeEmpty()
      h.refreshed.shouldBeEmpty()
      node.loadState shouldBe DownstreamLoadState.NOT_LOADED
      node.loadedChildren.shouldBeNull()
    }
  }

  "m2b: a CancellationException from a fetch is never folded into a failed Result" {
    runTest {
      val h = harness()
      val node = dsNode(205)
      h.roots = dsSection(node)
      h.jobsAnswer = { throw CancellationException("cancelled") }

      h.loader.load(node)
      settle(h)

      h.bridgeCalls.shouldBeEmpty()
      h.logs.shouldBeEmpty()
      h.refreshed.shouldBeEmpty()
      node.loadedChildren.shouldBeNull()
      node.shownPayload.shouldBeNull()
    }
  }

  "m3: an Error thrown off the UI thread is caught and logged by class, never escaping the shared scope" {
    runTest {
      val h = harness()
      val node = dsNode(205)
      h.roots = dsSection(node)
      val loader = DownstreamLoader(
        launchOffUi = { b -> launch { b() } },
        uiDispatch = { throw AssertionError("dispatch failed") },
        source = h.source,
        tree = h.tree,
        buildChildren = SidebarViewModel()::buildDownstreamChildren,
        warnLog = { h.logs += it },
      )

      loader.load(node)
      testScheduler.advanceUntilIdle()

      h.logs shouldContainExactly listOf("Downstream pipeline load failed: java.lang.AssertionError")
    }
  }

  // --- production source ---

  "ServiceDownstreamSource: jobs with include_retried, bridges, both pinned; the pin is the write gate" {
    val apiClient = mockk<GitLabApiClient>()
    val jobService = mockk<JobService>()
    val bridgeService = mockk<BridgeService>()
    every { apiClient.captureConnectionIf(any()) } answers {
      val accept = firstArg<(String) -> Boolean>()
      if (accept(DS_URL)) DS_SNAPSHOT else null
    }
    every { jobService.getJobsForPipeline(any(), any(), any(), any(), any()) } returns listOf(dsJob(1))
    every { bridgeService.getBridgesForPipeline(any(), any(), any(), any()) } returns emptyList()
    val source = ServiceDownstreamSource(apiClient, jobService, bridgeService)
    val active = { true }

    source.pinConnection("$DS_URL/", DS_FP) shouldBe DS_SNAPSHOT
    source.pinConnection(DS_URL, "fp-other").shouldBeNull()
    source.pinConnection("https://other.example.com", DS_FP).shouldBeNull()
    source.jobs("42", 205L, active, DS_SNAPSHOT) shouldBe listOf(dsJob(1))
    source.bridges("42", 205L, active, DS_SNAPSHOT).shouldBeEmpty()

    verify { jobService.getJobsForPipeline("42", 205L, active, DS_SNAPSHOT, includeRetried = true) }
    verify { bridgeService.getBridgesForPipeline("42", 205L, active, DS_SNAPSHOT) }
  }
})
