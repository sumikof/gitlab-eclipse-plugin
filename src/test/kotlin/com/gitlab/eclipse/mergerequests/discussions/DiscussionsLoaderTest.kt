package com.gitlab.eclipse.mergerequests.discussions

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.DiscussionService
import com.gitlab.eclipse.api.DiscussionsReadResult
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.UnstableConnectionException
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.views.sidebar.DiscussionLoadState
import com.gitlab.eclipse.views.sidebar.DiscussionsSectionNode
import com.gitlab.eclipse.views.sidebar.MessageNode
import com.gitlab.eclipse.views.sidebar.SidebarNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.Platform
import org.osgi.framework.Bundle
import java.io.IOException
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

private const val NODE_INSTANCE_URL = "https://gitlab.example.com"
private const val NODE_AUTH_FINGERPRINT = "fp-node"
private const val NODE_PROJECT_ID = 7L
private const val NODE_MR_IID = 42L

/**
 * Test double wiring for [DiscussionsLoader]: synchronous `runInBackground`/`runOnUi` fakes by
 * default (deterministic ordering), each deferrable into a queue so the registry can be mutated
 * *between* the background work and `finish` — that is how the guards get exercised.
 */
private class LoaderHarness(
  deferBackground: Boolean = false,
  deferUi: Boolean = false,
) {
  val discussionService = mockk<DiscussionService>()
  val apiClient = mockk<GitLabApiClient>()

  val node = DiscussionsSectionNode(
    sourceInstanceUrl = NODE_INSTANCE_URL,
    sourceAuthFingerprint = NODE_AUTH_FINGERPRINT,
    projectId = NODE_PROJECT_ID,
    mrIid = NODE_MR_IID,
    mrGid = "gid://gitlab/MergeRequest/99",
    mrSha = "abc123",
    namespaceWithPath = "group/project",
  )

  /** The same key `loadDiscussions` derives internally, for bumping generations in tests. */
  val key = DiscussionKey.of(NODE_INSTANCE_URL, NODE_AUTH_FINGERPRINT, NODE_PROJECT_ID, NODE_MR_IID, node.nodeId)

  val loadingChildren: List<SidebarNode> = listOf(MessageNode("loading-marker"))
  val successChildren: List<SidebarNode> = listOf(MessageNode("success-marker"))
  val failureChildren: List<SidebarNode> = listOf(MessageNode("failure-marker"))
  val gateRejectedChildren: List<SidebarNode> = listOf(MessageNode("gate-rejected-marker"))

  val pendingBackground = ArrayDeque<() -> Unit>()
  val pendingUi = ArrayDeque<() -> Unit>()
  private var insideUiHop = false

  val outcomes = mutableListOf<LoadOutcome>()
  val outcomesDeliveredInsideUiHop = mutableListOf<Boolean>()
  val notifications = mutableListOf<String>()
  var refreshCount = 0
    private set

  val loader = DiscussionsLoader(
    discussionService = discussionService,
    apiClient = apiClient,
    runInBackground = { task -> if (deferBackground) pendingBackground += task else task() },
    runOnUi = { task -> if (deferUi) pendingUi += task else runUiTask(task) },
    buildChildren = { _, _ -> successChildren },
    buildFailureChildren = { _, gateRejected -> if (gateRejected) gateRejectedChildren else failureChildren },
    buildLoadingChildren = { _ -> loadingChildren },
    refreshNode = { refreshCount++ },
    notify = { notifications += it },
  )

  fun load(force: Boolean = false) {
    loader.loadDiscussions(node, force) { outcome ->
      outcomes += outcome
      outcomesDeliveredInsideUiHop += insideUiHop
    }
  }

  fun runPendingBackground() {
    while (pendingBackground.isNotEmpty()) pendingBackground.removeFirst().invoke()
  }

  fun runPendingUi() {
    while (pendingUi.isNotEmpty()) runUiTask(pendingUi.removeFirst())
  }

  private fun runUiTask(task: () -> Unit) {
    insideUiHop = true
    try {
      task()
    } finally {
      insideUiHop = false
    }
  }

  fun givenConnection(instanceUrl: String = NODE_INSTANCE_URL, authFingerprint: String = NODE_AUTH_FINGERPRINT) {
    every { apiClient.captureConnection() } returns
      ConnectionSnapshot(instanceUrl, "tok-123", authFingerprint, 1L)
  }

  fun givenSuccess(result: DiscussionsReadResult = DiscussionsReadResult(true, emptyList(), null)) {
    givenConnection()
    every { discussionService.getDiscussions(any(), any(), any(), any(), any(), any()) } returns result
  }

  fun givenFailure(cause: Throwable) {
    givenConnection()
    every { discussionService.getDiscussions(any(), any(), any(), any(), any(), any()) } throws cause
  }

  fun verifyNoFetchIssued() {
    verify(exactly = 0) { discussionService.getDiscussions(any(), any(), any(), any(), any(), any()) }
  }
}

class DiscussionsLoaderTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  // The registry is a process-wide singleton: reset it around every test so no generation
  // numbers, epoch, or activation state leak between tests.
  beforeEach { DiscussionGenerationRegistry.resetForTest() }
  afterEach { DiscussionGenerationRegistry.resetForTest() }

  describe("the five outcomes, once each, on the UI thread") {
    it("success delivers Applied exactly once, inside the UI hop") {
      val h = LoaderHarness()
      h.givenSuccess()

      h.load()

      h.outcomes shouldContainExactly listOf(LoadOutcome.Applied)
      h.outcomesDeliveredInsideUiHop shouldContainExactly listOf(true)
    }

    it("force=false on an already LOADED node delivers Skipped exactly once, inside the UI hop, with no fetch") {
      val h = LoaderHarness()
      h.node.loadState = DiscussionLoadState.LOADED

      h.load(force = false)

      h.outcomes shouldContainExactly listOf(LoadOutcome.Skipped)
      h.outcomesDeliveredInsideUiHop shouldContainExactly listOf(true)
      h.verifyNoFetchIssued()
    }

    it("force=true on an already LOADED node issues the fetch (post-write re-fetch path)") {
      val h = LoaderHarness()
      h.node.loadState = DiscussionLoadState.LOADED
      h.givenSuccess()

      h.load(force = true)

      verify(exactly = 1) { h.discussionService.getDiscussions(any(), any(), any(), any(), any(), any()) }
      h.outcomes shouldContainExactly listOf(LoadOutcome.Applied)
    }

    it("a rejected gate delivers GateRejected exactly once, inside the UI hop") {
      val h = LoaderHarness()
      h.givenConnection(instanceUrl = "https://other.example.com")

      h.load()

      h.outcomes shouldContainExactly listOf(LoadOutcome.GateRejected)
      h.outcomesDeliveredInsideUiHop shouldContainExactly listOf(true)
    }

    it("a throwing fetch delivers Failed carrying that exact throwable, exactly once, inside the UI hop") {
      val h = LoaderHarness()
      val cause = IOException("connection reset")
      h.givenFailure(cause)

      h.load()

      h.outcomes shouldContainExactly listOf(LoadOutcome.Failed(cause))
      (h.outcomes.single() as LoadOutcome.Failed).cause shouldBeSameInstanceAs cause
      h.outcomesDeliveredInsideUiHop shouldContainExactly listOf(true)
    }

    it("two nodes for the same merge request complete independently: both Applied, neither superseded") {
      // The same MR appears under "Merge requests assigned to me" AND "For current branch" as two
      // DiscussionsSectionNodes; their keys differ only by nodeId. Real scenario: start A, start B
      // while A's result is still queued, deliver A, deliver B — before the per-node key, B's
      // start superseded A, which then stranded its node in LOADING forever.
      val h = LoaderHarness(deferUi = true)
      h.givenSuccess()
      val nodeB = DiscussionsSectionNode(
        sourceInstanceUrl = NODE_INSTANCE_URL,
        sourceAuthFingerprint = NODE_AUTH_FINGERPRINT,
        projectId = NODE_PROJECT_ID,
        mrIid = NODE_MR_IID,
        mrGid = "gid://gitlab/MergeRequest/99",
        mrSha = "abc123",
        namespaceWithPath = "group/project",
      )
      val outcomesA = mutableListOf<LoadOutcome>()
      val outcomesB = mutableListOf<LoadOutcome>()

      h.loader.loadDiscussions(h.node, force = false) { outcomesA += it }
      h.loader.loadDiscussions(nodeB, force = false) { outcomesB += it }
      h.runPendingUi()

      outcomesA shouldContainExactly listOf(LoadOutcome.Applied)
      outcomesB shouldContainExactly listOf(LoadOutcome.Applied)
      h.node.loadState shouldBe DiscussionLoadState.LOADED
      nodeB.loadState shouldBe DiscussionLoadState.LOADED
    }

    it("a superseded load delivers Superseded exactly once, inside the UI hop") {
      val h = LoaderHarness(deferUi = true)
      h.givenSuccess()

      h.load()
      DiscussionGenerationRegistry.nextGeneration(h.key)
      h.runPendingUi()

      h.outcomes shouldContainExactly listOf(LoadOutcome.Superseded)
      h.outcomesDeliveredInsideUiHop shouldContainExactly listOf(true)
    }
  }

  describe("connection gate") {
    it("a changed instance URL is rejected with zero API calls") {
      val h = LoaderHarness()
      h.givenConnection(instanceUrl = "https://other.example.com")

      h.load()

      h.outcomes shouldContainExactly listOf(LoadOutcome.GateRejected)
      h.verifyNoFetchIssued()
    }

    it("account-switch protection: same instance URL but a different authFingerprint issues zero API calls") {
      val h = LoaderHarness()
      h.givenConnection(instanceUrl = NODE_INSTANCE_URL, authFingerprint = "fp-other-account")

      h.load()

      h.outcomes shouldContainExactly listOf(LoadOutcome.GateRejected)
      h.verifyNoFetchIssued()
    }

    it("UnstableConnectionException from captureConnection yields GateRejected, zero API calls, nothing escapes") {
      val h = LoaderHarness()
      every { h.apiClient.captureConnection() } throws UnstableConnectionException()

      h.load()

      h.outcomes shouldContainExactly listOf(LoadOutcome.GateRejected)
      h.verifyNoFetchIssued()
    }

    it("a non-UnstableConnectionException from captureConnection funnels into Failed and clears the placeholder") {
      val h = LoaderHarness()
      val cause = IllegalStateException("secure storage read failed")
      every { h.apiClient.captureConnection() } throws cause

      h.load()

      h.outcomes shouldContainExactly listOf(LoadOutcome.Failed(cause))
      (h.outcomes.single() as LoadOutcome.Failed).cause shouldBeSameInstanceAs cause
      h.outcomesDeliveredInsideUiHop shouldContainExactly listOf(true)
      h.verifyNoFetchIssued()
      h.node.loadState shouldBe DiscussionLoadState.FAILED
      h.node.loadedChildren shouldBeSameInstanceAs h.failureChildren
    }

    it("CancellationException from captureConnection propagates and delivers no outcome") {
      val h = LoaderHarness()
      every { h.apiClient.captureConnection() } throws CancellationException("cancelled during capture")

      shouldThrow<CancellationException> { h.load() }

      h.outcomes.shouldBeEmpty()
      h.verifyNoFetchIssued()
    }
  }

  describe("freshness guard ordering (evaluated before any branch on the result)") {
    it("a successful but superseded result yields Superseded, leaving loadState and loadedChildren untouched") {
      val h = LoaderHarness(deferUi = true)
      h.givenSuccess()

      h.load()
      DiscussionGenerationRegistry.nextGeneration(h.key)
      h.runPendingUi()

      h.outcomes shouldContainExactly listOf(LoadOutcome.Superseded)
      h.node.loadState shouldBe DiscussionLoadState.LOADING
      h.node.loadedChildren shouldBeSameInstanceAs h.loadingChildren
      h.node.canCreateNote shouldBe false
    }

    it("a superseded FAILURE yields Superseded: load A, bump the same key's generation, deliver A's failure") {
      val h = LoaderHarness(deferUi = true)
      h.givenFailure(IOException("late failure of load A"))

      h.load()
      DiscussionGenerationRegistry.nextGeneration(h.key)
      h.runPendingUi()

      h.outcomes shouldContainExactly listOf(LoadOutcome.Superseded)
      h.node.loadState shouldBe DiscussionLoadState.LOADING
      h.node.loadedChildren shouldBeSameInstanceAs h.loadingChildren
    }

    it("a gate-rejected result whose generation is no longer latest yields Superseded with no node mutation") {
      val h = LoaderHarness(deferUi = true)
      h.givenConnection(authFingerprint = "fp-other-account")

      h.load()
      DiscussionGenerationRegistry.nextGeneration(h.key)
      h.runPendingUi()

      h.outcomes shouldContainExactly listOf(LoadOutcome.Superseded)
      h.node.loadState shouldBe DiscussionLoadState.LOADING
      h.node.loadedChildren shouldBeSameInstanceAs h.loadingChildren
      h.notifications.shouldBeEmpty()
    }

    it("refreshNode is not called at all on the superseded path (only the single start refresh happened)") {
      val h = LoaderHarness(deferUi = true)
      h.givenSuccess()

      h.load()
      val refreshesAtStart = h.refreshCount
      DiscussionGenerationRegistry.nextGeneration(h.key)
      h.runPendingUi()

      refreshesAtStart shouldBe 1
      h.refreshCount shouldBe 1
    }
  }

  describe("lifecycle guard") {
    it("active == false at finish time: onOutcome is never called and the node is untouched") {
      val h = LoaderHarness(deferUi = true)
      h.givenSuccess()

      h.load()
      DiscussionGenerationRegistry.onDeactivate()
      h.runPendingUi()

      h.outcomes.shouldBeEmpty()
      h.node.loadState shouldBe DiscussionLoadState.LOADING
      h.node.loadedChildren shouldBeSameInstanceAs h.loadingChildren
      h.refreshCount shouldBe 1
    }

    it("currentEpoch != startEpoch at finish time (stop then start): onOutcome is never called") {
      val h = LoaderHarness(deferUi = true)
      h.givenSuccess()

      h.load()
      DiscussionGenerationRegistry.onDeactivate()
      DiscussionGenerationRegistry.onActivate()
      h.runPendingUi()

      h.outcomes.shouldBeEmpty()
      h.node.loadState shouldBe DiscussionLoadState.LOADING
    }

    it("lifecycle guard runs before freshness guard: with both failing the result is silence, not Superseded") {
      val h = LoaderHarness(deferUi = true)
      h.givenSuccess()

      h.load()
      DiscussionGenerationRegistry.nextGeneration(h.key)
      DiscussionGenerationRegistry.onDeactivate()
      h.runPendingUi()

      h.outcomes.shouldBeEmpty()
    }
  }

  describe("terminal UI effects") {
    it("Applied sets canCreateNote from the result even when discussions is empty") {
      val h = LoaderHarness()
      h.givenSuccess(DiscussionsReadResult(canCreateNote = true, discussions = emptyList(), truncation = null))

      h.load()

      h.node.canCreateNote shouldBe true
      h.outcomes shouldContainExactly listOf(LoadOutcome.Applied)
    }

    it("Applied sets loadState LOADED and loadedChildren to what buildChildren returned") {
      val h = LoaderHarness()
      h.givenSuccess()

      h.load()

      h.node.loadState shouldBe DiscussionLoadState.LOADED
      h.node.loadedChildren shouldBeSameInstanceAs h.successChildren
    }

    it("Failed sets loadState FAILED and replaces the loading placeholder with buildFailureChildren(node, false)") {
      val h = LoaderHarness()
      h.givenFailure(IOException("boom"))

      h.load()

      h.node.loadState shouldBe DiscussionLoadState.FAILED
      h.node.loadedChildren shouldBeSameInstanceAs h.failureChildren
    }

    it("GateRejected clears the loading display via buildFailureChildren(node, true), notifying once, no HTTP") {
      val h = LoaderHarness()
      h.givenConnection(authFingerprint = "fp-other-account")

      h.load()

      h.node.loadState shouldBe DiscussionLoadState.FAILED
      h.node.loadedChildren shouldBeSameInstanceAs h.gateRejectedChildren
      h.notifications shouldContainExactly listOf(DiscussionsLoader.CONNECTION_CHANGED_MESSAGE)
      h.verifyNoFetchIssued()
    }

    it("at start, loadState becomes LOADING and buildLoadingChildren + refreshNode ran before the background work") {
      val h = LoaderHarness(deferBackground = true)
      h.givenSuccess()

      h.load()

      h.node.loadState shouldBe DiscussionLoadState.LOADING
      h.node.loadedChildren shouldBeSameInstanceAs h.loadingChildren
      h.refreshCount shouldBe 1
      h.pendingBackground.size shouldBe 1
      h.verifyNoFetchIssued()

      h.runPendingBackground()
      h.outcomes shouldContainExactly listOf(LoadOutcome.Applied)
    }
  }

  describe("deadline and cancellation") {
    it("getDiscussions is called with DiscussionService.DISCUSSIONS_DEADLINE") {
      val h = LoaderHarness()
      h.givenConnection()
      val deadlineSlot = slot<Duration>()
      every {
        h.discussionService.getDiscussions(any(), any(), any(), capture(deadlineSlot), any(), any())
      } returns DiscussionsReadResult(canCreateNote = false, discussions = emptyList(), truncation = null)

      h.load()

      deadlineSlot.captured shouldBe DiscussionService.DISCUSSIONS_DEADLINE
    }

    it("CancellationException from getDiscussions propagates out of the background block instead of becoming Failed") {
      val h = LoaderHarness()
      h.givenFailure(CancellationException("cancelled"))

      shouldThrow<CancellationException> { h.load() }

      h.outcomes.shouldBeEmpty()
    }
  }

  describe("failure logging") {
    it("logs the exception's class name and never the exception message (token-shaped marker must not appear)") {
      val log = mockk<ILog>(relaxUnitFun = true)
      every { Platform.getLog(any<Bundle>()) } returns log
      val h = LoaderHarness()
      val marker = "Bearer glpat-SECRET-MARKER-123"
      h.givenFailure(IllegalStateException(marker))

      h.load()

      val message = slot<String>()
      verify(exactly = 1) { log.error(capture(message)) }
      message.captured shouldContain "exceptionType=java.lang.IllegalStateException"
      message.captured shouldNotContain marker
      message.captured shouldNotContain "glpat"
      // Sanity: the exception really carried the marker — the log line's cleanliness is not vacuous.
      (h.outcomes.single() as LoadOutcome.Failed).cause.message shouldBe marker
    }
  }

  describe("FetchOutcome.Failed.toString") {
    // FetchOutcome は private sealed interface(DiscussionsLoader.kt:60)なので名前で構築できない。
    // production の可視性は変えず、リフレクションで組み立てて出力形だけを固定する。
    val binaryName =
      "com.gitlab.eclipse.mergerequests.discussions.DiscussionsLoader\$FetchOutcome\$Failed"

    fun render(cause: Throwable): String {
      val failedClass = Class.forName(binaryName)
      val ctor = failedClass.declaredConstructors.single().apply { isAccessible = true }
      return "${ctor.newInstance(cause)}"
    }

    it("keeps the cause's message out and its type in") {
      render(java.io.IOException("https://gitlab.example.com/secret")) shouldBe
        "FetchOutcome.Failed(type=java.io.IOException)"
    }

    // A2(a): 例外型は「秘匿値の許された投影」。型を変えたら出力も変わる。
    // これが無いと toString を固定の定数にしても通る(設計 §22.1 の (v) 群)。
    it("reflects a different cause type") {
      render(IllegalStateException("https://gitlab.example.com/secret")) shouldBe
        "FetchOutcome.Failed(type=java.lang.IllegalStateException)"
    }
  }
})
