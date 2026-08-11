package com.gitlab.eclipse.mergerequests.discussions

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.DiscussionService
import com.gitlab.eclipse.api.DiscussionsReadResult
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.views.sidebar.DiscussionLoadState
import com.gitlab.eclipse.views.sidebar.DiscussionsSectionNode
import com.gitlab.eclipse.views.sidebar.MessageNode
import com.gitlab.eclipse.views.sidebar.SidebarNode
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

private const val RELOAD_INSTANCE_URL = "https://gitlab.example.com"
private const val RELOAD_AUTH_FINGERPRINT = "fp-reload"
private const val RELOAD_PROJECT_ID = 7L
private const val RELOAD_MR_IID = 42L

/**
 * The same synchronous fake wiring [DiscussionsLoaderTest] uses, reduced to what this spec needs:
 * a mocked [DiscussionService] whose call count is the assertion, and a node whose `loadState` the
 * test sets directly.
 */
private class ReloadHarness {
  val discussionService = mockk<DiscussionService>()
  val apiClient = mockk<GitLabApiClient>()

  val node = DiscussionsSectionNode(
    sourceInstanceUrl = RELOAD_INSTANCE_URL,
    sourceAuthFingerprint = RELOAD_AUTH_FINGERPRINT,
    projectId = RELOAD_PROJECT_ID,
    mrIid = RELOAD_MR_IID,
    mrGid = "gid://gitlab/MergeRequest/99",
    mrSha = "abc123",
    namespaceWithPath = "group/project",
  )

  val outcomes = mutableListOf<LoadOutcome>()

  private val successChildren: List<SidebarNode> = listOf(MessageNode("success-marker"))

  val loader = DiscussionsLoader(
    discussionService = discussionService,
    apiClient = apiClient,
    runInBackground = { task -> task() },
    runOnUi = { task -> task() },
    buildChildren = { _, _ -> successChildren },
    buildFailureChildren = { _, _ -> emptyList() },
    buildLoadingChildren = { _ -> emptyList() },
    refreshNode = { },
    notify = { },
  )

  fun givenSuccess() {
    // The gate compares the instance url inside captureConnectionIf, before the credential is read
    // (issue #49), so the stub honors the predicate the way the real client does.
    val snapshot = ConnectionSnapshot(RELOAD_INSTANCE_URL, "tok-123", RELOAD_AUTH_FINGERPRINT, 1L)
    every { apiClient.captureConnectionIf(any()) } answers {
      val accept = firstArg<(String) -> Boolean>()
      if (accept(RELOAD_INSTANCE_URL)) snapshot else null
    }
    every { discussionService.getDiscussions(any(), any(), any(), any(), any(), any()) } returns
      DiscussionsReadResult(true, emptyList(), null)
  }

  fun load(force: Boolean) = loader.loadDiscussions(node, force) { outcomes += it }
}

/**
 * Design FR-10 / AC-11 — the post-write re-fetch. `GitLabSidebarView.reloadDiscussions` is SWT-bound
 * and cannot be instantiated in this headless container, so its one load-bearing decision — that it
 * passes `force = true` — is asserted one level down, on the [DiscussionsLoader] it delegates to.
 *
 * The two cases below are the whole reason the flag matters: after a successful write the section is
 * already `LOADED`, so `force = false` would skip the fetch entirely and leave the user reading a
 * stale thread.
 */
class ReloadDiscussionsTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  beforeEach { DiscussionGenerationRegistry.resetForTest() }
  afterEach { DiscussionGenerationRegistry.resetForTest() }

  describe("re-fetching an already LOADED discussions section") {
    it("force = true issues exactly one fetch and reaches Applied") {
      val h = ReloadHarness()
      h.node.loadState = DiscussionLoadState.LOADED
      h.givenSuccess()

      h.load(force = true)

      verify(exactly = 1) { h.discussionService.getDiscussions(any(), any(), any(), any(), any(), any()) }
      h.outcomes shouldContainExactly listOf(LoadOutcome.Applied)
    }

    it("force = false issues no fetch and yields Skipped, which is why reloadDiscussions must force") {
      val h = ReloadHarness()
      h.node.loadState = DiscussionLoadState.LOADED
      h.givenSuccess()

      h.load(force = false)

      verify(exactly = 0) { h.discussionService.getDiscussions(any(), any(), any(), any(), any(), any()) }
      h.outcomes shouldContainExactly listOf(LoadOutcome.Skipped)
    }
  }
})
