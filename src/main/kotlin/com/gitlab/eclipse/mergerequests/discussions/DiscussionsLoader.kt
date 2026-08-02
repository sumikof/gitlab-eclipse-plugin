package com.gitlab.eclipse.mergerequests.discussions

import com.gitlab.eclipse.api.DiscussionService
import com.gitlab.eclipse.api.DiscussionsReadResult
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.ci.actions.pinnedConnectionFor
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.sidebar.DiscussionLoadState
import com.gitlab.eclipse.views.sidebar.DiscussionsSectionNode
import com.gitlab.eclipse.views.sidebar.SidebarNode
import kotlin.coroutines.cancellation.CancellationException

/**
 * Concurrency and lifecycle core of the discussions section (design phase5a-pr1): starts a
 * background fetch for one [DiscussionsSectionNode] and decides — on the UI thread — whether
 * the result may touch the tree.
 *
 * **SWT-free by construction**: this container cannot run SWT, so every thread hop
 * ([runInBackground], [runOnUi]) and every UI effect ([buildChildren], [buildFailureChildren],
 * [buildLoadingChildren], [refreshNode], [notify]) arrives as an injected function, which makes
 * the guard ordering below directly unit-testable. Do not import anything from
 * `org.eclipse.swt` or `org.eclipse.ui` here. A later task supplies the real implementations.
 *
 * **Completion contract.** While the plugin is running, `onOutcome` is invoked **exactly once,
 * on the UI thread**. The single exception is lifecycle termination
 * ([DiscussionGenerationRegistry.active] `== false`, or
 * [DiscussionGenerationRegistry.currentEpoch] no longer equal to the epoch captured at start):
 * the callback's ownership is then deliberately abandoned and `onOutcome` is **not** called.
 * That exception is intentional, not a contract violation — when the plugin is stopping, doing
 * nothing in the UI outranks notifying, because a callback fired during shutdown would run
 * dialogs or re-fetches downstream and break the gate-before-anything invariant. Callers may
 * assume "no callback" means "we were shut down".
 *
 * The freshness guard ([DiscussionGenerationRegistry.isLatest]) is evaluated for **every** kind
 * of result, before any branch on what the result is: a stale request's *failure* arriving
 * after a newer request already settled the node must not overwrite the fresh tree with an
 * error node, so which-request-is-current is decided independently of success or failure.
 *
 * @param buildFailureChildren its `Boolean` is `gateRejected` — true when the failure was the
 *   connection gate rather than a fetch error, so the caller can word the message differently.
 * @param runInBackground runs its argument off the UI thread; no default — the caller supplies it.
 * @param runOnUi runs its argument on the UI thread; no default — the caller supplies it.
 */
class DiscussionsLoader(
  private val discussionService: DiscussionService = service(),
  private val apiClient: GitLabApiClient = service(),
  private val runInBackground: (() -> Unit) -> Unit,
  private val runOnUi: (() -> Unit) -> Unit,
  private val buildChildren: (DiscussionsSectionNode, DiscussionsReadResult) -> List<SidebarNode>,
  private val buildFailureChildren: (DiscussionsSectionNode, Boolean) -> List<SidebarNode>,
  private val buildLoadingChildren: (DiscussionsSectionNode) -> List<SidebarNode>,
  private val refreshNode: (DiscussionsSectionNode) -> Unit,
  private val notify: (String) -> Unit,
) {

  private val logger by lazy { logger<DiscussionsLoader>() }

  /** What the background stage produced, carried to the UI-thread `finish` stage. */
  private sealed interface FetchOutcome {
    data class Fetched(val result: DiscussionsReadResult) : FetchOutcome
    data class Failed(val cause: Throwable) : FetchOutcome
    object GateRejected : FetchOutcome
  }

  /**
   * Starts (or skips) a discussions load for [node]. Must be called on the UI thread —
   * [DiscussionsSectionNode.loadState] is UI-thread-confined, so the not-loaded check and the
   * `LOADING` write below are atomic without any lock. [force] `= true` issues the fetch even
   * when the node is already `LOADED` (the post-write re-fetch path). [onOutcome] is delivered
   * per the class-level completion contract.
   */
  fun loadDiscussions(
    node: DiscussionsSectionNode,
    force: Boolean,
    onOutcome: (LoadOutcome) -> Unit,
  ) {
    if (!force && node.loadState == DiscussionLoadState.LOADED) {
      runOnUi { onOutcome(LoadOutcome.Skipped) }
      return
    }
    node.loadState = DiscussionLoadState.LOADING
    val startEpoch = DiscussionGenerationRegistry.currentEpoch
    val key = DiscussionKey.of(node.sourceInstanceUrl, node.sourceAuthFingerprint, node.projectId, node.mrIid)
    val generation = DiscussionGenerationRegistry.nextGeneration(key)
    node.loadedChildren = buildLoadingChildren(node)
    refreshNode(node)
    runInBackground {
      val fetch = fetchInBackground(node)
      runOnUi { finish(node, key, generation, startEpoch, fetch, onOutcome) }
    }
  }

  /**
   * Background stage: gate first, then fetch. A null from [pinnedConnectionFor] (instance URL
   * changed, credential changed on the same URL, or settings mid-change — it already swallows
   * `UnstableConnectionException`) means gate-rejected and **no HTTP is issued at all**.
   * [CancellationException] is rethrown, never converted into a failure — swallowing it breaks
   * scope cancellation (see `GitLabSidebarView`'s background blocks).
   */
  private fun fetchInBackground(node: DiscussionsSectionNode): FetchOutcome {
    val connection = pinnedConnectionFor(apiClient, node.sourceInstanceUrl, node.sourceAuthFingerprint)
      ?: return FetchOutcome.GateRejected
    return try {
      FetchOutcome.Fetched(
        discussionService.getDiscussions(
          connection,
          node.namespaceWithPath,
          node.mrIid,
          DiscussionService.DISCUSSIONS_DEADLINE,
        ),
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      FetchOutcome.Failed(e)
    }
  }

  /**
   * UI-thread stage, three guards in a fixed order:
   *
   * 1. **Lifecycle guard** — plugin stopped ([DiscussionGenerationRegistry.active] false) or
   *    stopped-and-restarted (epoch changed): discard silently, without calling [onOutcome] and
   *    without touching the node. This is the documented contract exception.
   * 2. **Freshness guard** — evaluated for every kind of result before any branch on it: a
   *    superseded request reports [LoadOutcome.Superseded] and touches neither `loadState` nor
   *    the tree; the newer in-flight request will itself settle `LOADED` or `FAILED`.
   * 3. **Result branch** — this request is current; exactly one [onOutcome] call happens, and
   *    every terminal branch replaces the loading placeholder (setting `loadState` alone would
   *    leave "Loading…" on screen — even the gate-rejected branch, which issued no HTTP, must
   *    clear it or the section reads "Loading…" forever with no way to retry).
   */
  private fun finish(
    node: DiscussionsSectionNode,
    key: DiscussionKey,
    generation: Long,
    startEpoch: Long,
    fetch: FetchOutcome,
    onOutcome: (LoadOutcome) -> Unit,
  ) {
    if (!DiscussionGenerationRegistry.active || DiscussionGenerationRegistry.currentEpoch != startEpoch) return
    if (!DiscussionGenerationRegistry.isLatest(key, generation)) {
      onOutcome(LoadOutcome.Superseded)
      return
    }
    when (fetch) {
      is FetchOutcome.GateRejected -> applyGateRejected(node, onOutcome)
      is FetchOutcome.Failed -> applyFailed(node, fetch.cause, onOutcome)
      is FetchOutcome.Fetched -> applyFetched(node, fetch.result, onOutcome)
    }
  }

  /** Gate-rejected terminal branch: failure children (gateRejected = true), notify, then callback. */
  private fun applyGateRejected(node: DiscussionsSectionNode, onOutcome: (LoadOutcome) -> Unit) {
    node.loadedChildren = buildFailureChildren(node, true)
    refreshNode(node)
    node.loadState = DiscussionLoadState.FAILED
    notify(CONNECTION_CHANGED_MESSAGE)
    onOutcome(LoadOutcome.GateRejected)
  }

  /**
   * Failed terminal branch. The audit line carries `exceptionType=<class name>` **only**: never
   * the exception object, the token, the connection URL's credential, the GraphQL query, the
   * variables, the response, or any note text — a Phase 4 review found a real path where an
   * exception message carried `Bearer <token>` into the Eclipse Error Log.
   */
  private fun applyFailed(node: DiscussionsSectionNode, cause: Throwable, onOutcome: (LoadOutcome) -> Unit) {
    logger.error("Discussions fetch failed: exceptionType=${cause.javaClass.name}")
    node.loadedChildren = buildFailureChildren(node, false)
    refreshNode(node)
    node.loadState = DiscussionLoadState.FAILED
    onOutcome(LoadOutcome.Failed(cause))
  }

  /**
   * Applied terminal branch. `canCreateNote` is assigned from the result so a merge request
   * with **zero** discussions still offers the "comment on this merge request" entry point —
   * note-level permissions cannot supply it because there are no notes.
   */
  private fun applyFetched(
    node: DiscussionsSectionNode,
    result: DiscussionsReadResult,
    onOutcome: (LoadOutcome) -> Unit,
  ) {
    node.canCreateNote = result.canCreateNote
    node.loadedChildren = buildChildren(node, result)
    refreshNode(node)
    node.loadState = DiscussionLoadState.LOADED
    onOutcome(LoadOutcome.Applied)
  }

  companion object {
    /**
     * User-facing message for a gate-rejected load. Deliberately short and non-technical, and
     * deliberately free of the instance URL, the account name, and the auth fingerprint.
     */
    const val CONNECTION_CHANGED_MESSAGE = "GitLab connection changed. Discussions were not loaded."
  }
}
