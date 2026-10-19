package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.model.GitLabJob
import com.gitlab.eclipse.api.model.GitLabPipeline
import com.gitlab.eclipse.mergerequests.CurrentBranchInfo
import com.gitlab.eclipse.views.sidebar.queries.CustomQuery
import com.gitlab.eclipse.views.sidebar.queries.QueryConfig
import com.gitlab.eclipse.views.sidebar.queries.QueryOutcome

/**
 * A pipeline paired with its (possibly still-failed) jobs fetch, mirroring the
 * `(pipeline, jobsResult)` pair [SidebarViewModel.buildPipelineNode] already consumes,
 * plus the non-secret tags of the connection both GETs were pinned to (design doc
 * §8.4/§8.5): [sourceInstanceUrl] and [sourceAuthFingerprint] are captured atomically at
 * refresh start and flow onto the nodes so write handlers can verify the connection has
 * not changed since the data was fetched. Never the token itself.
 */
data class PipelineSnapshot(
  val pipeline: GitLabPipeline,
  val jobsResult: Result<List<GitLabJob>>,
  val sourceInstanceUrl: String,
  val sourceAuthFingerprint: String,
)

/**
 * Input to [SidebarViewModel.buildCurrentBranchSection]'s new overload (design doc §6.6):
 * the MR lookup and the pipeline lookup are independent async tasks (Task 9 wires each to its
 * own coroutine), so this type lets the section be (re)built from whichever of the two has
 * settled — a slow/failed pipeline fetch must not hide an already-resolved MR, and vice versa.
 */
sealed interface CurrentBranchSectionInput

/** No repository is resolved for the workspace (or none is selected yet). */
object NoRepository : CurrentBranchSectionInput

/**
 * A repository is resolved; [mr] and [pipeline] are each `null` while their corresponding fetch
 * is still in flight, or the settled [Result] once it completes. `pipeline` further distinguishes
 * "still loading" (`null`) from "loaded, and there is no current pipeline" (`Result.success(null)`)
 * from "loaded, here it is" (`Result.success(PipelineSnapshot)`).
 */
data class Resolved(
  val mr: Result<CurrentBranchInfo>?,
  val pipeline: Result<PipelineSnapshot?>?,
) : CurrentBranchSectionInput

/**
 * One async fetch's outcome slot within a refresh generation (design doc §6.6/§9): [Pending]
 * until the fetch settles, then [Settled] with whatever value it produced. Distinct from
 * `Result` — a settled slot may well hold a failed `Result`; the slot only says "this unit
 * has reported in", so [SidebarRefreshCoordinator] can compose the units that have settled
 * without gating on the ones that haven't.
 */
sealed interface Slot<out T> {
  object Pending : Slot<Nothing>
  data class Settled<T>(val value: T) : Slot<T>
}

/**
 * The query roots' slot of one refresh (ISSUE-01 design §7): the [config] snapshot read on the UI thread at refresh
 * start, its [queries] in config order (snapshotted once — [QueryConfig.Defaults] hands out fresh copies on every
 * access, and the composer memo keys on query identity), and one outcome [Slot] per query id, all [Slot.Pending] until
 * that query settles. Ids are unique by construction ([com.gitlab.eclipse.views.sidebar.queries.CustomQueryCodec]).
 * A settings change passes [queries] explicitly — [config]'s list with unchanged queries carried over from the previous
 * slot ([com.gitlab.eclipse.views.sidebar.queries.carryOverUnchanged]) so their memoized roots survive. UI thread only.
 */
class QueriesSlot(val config: QueryConfig, val queries: List<CustomQuery> = config.queries) {

  val outcomes: MutableMap<String, Slot<QueryOutcome>> =
    queries.associateTo(LinkedHashMap()) { it.id to Slot.Pending }
}

/**
 * All top-level fetch slots of one refresh generation (design doc §9/R7-R9). The view creates a fresh instance per
 * refresh with a strictly increasing [generation], mutates the slots on the UI thread as each unit's fetch settles,
 * and re-composes via [SidebarRefreshCoordinator.compose] after each mutation. A superseded generation's instance is
 * simply abandoned — the coordinator resets its memo when [generation] changes, so a stale generation's values can
 * never leak into a newer compose. The coordinator memo keys by payload identity, so an instance it has already
 * composed is treated as unchanged and skips a rebuild: a full refresh therefore settles freshly constructed payloads
 * (QueryOutcome/CurrentBranchInfo/PipelineSnapshot/Throwable), while the CI-04 soft refresh deliberately keeps the
 * shown `Result` instance of a side whose new value is structurally equal — that is how an auto refresh avoids
 * rebuilding (and collapsing) unchanged nodes ([CurrentBranchSectionFetcher]).
 *
 * [currentBranchEpoch] orders the soft refreshes of the current-branch section within one generation: a soft refresh
 * bumps it instead of [generation] (so the queries slot and the memo survive), and its updates apply only while their
 * epoch is still the latest. [queriesEpoch] does the same for the query roots (a settings change re-runs the queries
 * within the generation). The two epochs are independent: neither path reads or bumps the other's. UI thread only.
 */
class RefreshSlots(val generation: Long) {
  var queries: QueriesSlot = QueriesSlot(QueryConfig.Defaults)
  var queriesEpoch: Long = 0
  var currentBranch: Slot<CurrentBranchSectionInput> = Slot.Pending
  var currentBranchEpoch: Long = 0

  /**
   * The repository (git dir) [currentBranch]'s value describes; null for [NoRepository] or before
   * anything settled. Written in the same UI-thread step as [currentBranch], so a soft refresh can
   * tell whether the shown payload belongs to the repository it is about to fetch.
   */
  var currentBranchGitDir: String? = null
}

/** Which part of the slots an apply belongs to, and so which epoch it must still match (ISSUE-01 design §7, F5). */
sealed interface ApplyScope {
  /** Not tied to a section epoch (a mode change re-composes everything): only the generation must be current. */
  object Generation : ApplyScope

  /** A query-root apply launched under [RefreshSlots.queriesEpoch] == [epoch]. */
  data class Queries(val epoch: Long) : ApplyScope

  /** A current-branch apply launched under [RefreshSlots.currentBranchEpoch] == [epoch]. */
  data class CurrentBranch(val epoch: Long) : ApplyScope
}

/**
 * Whether an apply scheduled for these slots may still touch them (CI-04 design §2.4/§4, ISSUE-01 design §7): only
 * while their [RefreshSlots.generation] is current and — for a scoped apply — only while the epoch of ITS section is
 * still the latest, so a superseded soft refresh or queries run paints nothing. A queries apply never compares against
 * [RefreshSlots.currentBranchEpoch] and vice versa. UI thread only.
 */
internal fun RefreshSlots.acceptsApply(isCurrentGeneration: (Long) -> Boolean, scope: ApplyScope): Boolean =
  isCurrentGeneration(generation) &&
    when (scope) {
      ApplyScope.Generation -> true
      is ApplyScope.Queries -> queriesEpoch == scope.epoch
      is ApplyScope.CurrentBranch -> currentBranchEpoch == scope.epoch
    }
