package com.gitlab.eclipse.views.sidebar.queries

import com.gitlab.eclipse.views.sidebar.Slot

/**
 * What a queries run may take over from the run before it, so a settings change re-fetches only what changed
 * (design §7, F3). Read by [CustomQueryRunner] on IO threads: implementations must be immutable.
 */
interface QueryReuse {
  /** Stage (a): the query already shows its earlier outcome; it must not run at all. */
  fun isSettled(queryId: String): Boolean

  /** Stage (b): the earlier list outcome to settle with when [plan] sends the same request as before, else null. */
  fun outcomeFor(queryId: String, plan: QueryPlan.Run): QueryOutcome?

  /** A full refresh: nothing is reused. */
  object None : QueryReuse {
    override fun isSettled(queryId: String): Boolean = false

    override fun outcomeFor(queryId: String, plan: QueryPlan.Run): QueryOutcome? = null
  }
}

/**
 * The reuse decisions of one settings change, taken on the UI thread by [CustomQueryResultCache.reuseFor] and then
 * read on IO threads; immutable and detached from the cache. [settled] holds the stage (a) outcomes the view shows
 * at once; stage (b) compares request prints against the snapshot taken with it.
 */
class CachedQueryReuse internal constructor(
  val settled: Map<String, QueryOutcome>,
  private val requests: Map<String, Pair<String, QueryOutcome>>,
) : QueryReuse {
  override fun isSettled(queryId: String): Boolean = queryId in settled

  override fun outcomeFor(queryId: String, plan: QueryPlan.Run): QueryOutcome? =
    requests[queryId]?.takeIf { it.first == QueryFingerprints.request(plan) }?.second
}

/**
 * [next] in its order, with each query whose id and stored JSON (or invalid element) are unchanged replaced by its
 * [previous] instance. The roots composer memoizes by query identity, so a settings change leaves an untouched
 * query's root — and the expansion and discussion keys beneath it — in place. Pure.
 */
fun carryOverUnchanged(previous: List<CustomQuery>, next: List<CustomQuery>): List<CustomQuery> {
  val byId = previous.associateBy { it.id }
  return next.map { query ->
    byId[query.id]?.takeIf { it.json == query.json && it.invalidElement == query.invalidElement } ?: query
  }
}

/**
 * Stage (a) and the stage (b) snapshot for [queries] (design §7): a query whose [previous] slot is settled with a LIST
 * outcome ([QueryOutcome.Issues] / [QueryOutcome.MergeRequests]) and whose config print equals the cached one is
 * settled with the cached outcome; a failure or message outcome (Failed, NeedsProject, …) always re-runs, so a
 * settings change is also a retry. Every other query with a cached list outcome and request print goes into the stage
 * (b) snapshot. UI thread (the cache is not thread-safe).
 */
fun CustomQueryResultCache.reuseFor(
  queries: List<CustomQuery>,
  previous: Map<String, Slot<QueryOutcome>>,
): CachedQueryReuse {
  val settled = LinkedHashMap<String, QueryOutcome>()
  val requests = HashMap<String, Pair<String, QueryOutcome>>()
  queries.forEach { query ->
    val wasSettled = previous[query.id] is Slot.Settled
    val cached = if (wasSettled) reusableOutcome(query.id, QueryFingerprints.config(query)) else null
    val reusable = cached?.takeIf(::isList)
    if (reusable != null) {
      settled[query.id] = reusable
    } else {
      listRequest(query.id)?.let { requests[query.id] = it }
    }
  }
  return CachedQueryReuse(settled, requests)
}

private fun isList(outcome: QueryOutcome): Boolean =
  outcome is QueryOutcome.Issues || outcome is QueryOutcome.MergeRequests
