package com.gitlab.eclipse.views.sidebar.queries

import com.gitlab.eclipse.views.sidebar.MessageNode
import com.gitlab.eclipse.views.sidebar.QueriesSlot
import com.gitlab.eclipse.views.sidebar.QueryRootNode
import com.gitlab.eclipse.views.sidebar.SidebarNode
import com.gitlab.eclipse.views.sidebar.SidebarViewMode
import com.gitlab.eclipse.views.sidebar.SidebarViewModel
import com.gitlab.eclipse.views.sidebar.Slot
import com.gitlab.eclipse.views.sidebar.collectDiscussionSectionNodeIds

/** Root label of a query whose name is missing or blank. */
const val UNNAMED_QUERY_LABEL = "(unnamed query)"

/** Child of a query root with zero items and no `noItemText` (VS Code's runtime default). */
const val NO_ITEMS_MESSAGE = "No items found."

/** Reserved root id of the "setting could not be read" root (never a query id: ids come from the codec). */
const val UNREADABLE_ROOT_ID = "__unreadable"

/** Reserved root id of the "no queries configured" root. */
const val EMPTY_ROOT_ID = "__empty"

/** The generic failure text, shared by the query roots and the rest of the sidebar (design §12). */
internal const val LOAD_FAILED_MESSAGE = "Failed to load — see the Error Log."

private const val CONFIG_ROOT_LABEL = "Sidebar queries"
private const val UNREADABLE_MESSAGE =
  "Sidebar queries could not be read. Fix or restore them in Preferences > GitLab Duo > Sidebar Queries."
private const val EMPTY_MESSAGE = "No sidebar queries. Add them in Preferences > GitLab Duo > Sidebar Queries."
private const val TRUNCATED_MESSAGE = "Showing the first 2000 results."
private const val INVALID_PREFIX = "Invalid query: "

/** The cap notice appended after the items of a truncated result (design §12), or nothing. */
internal fun truncationNotice(truncated: Boolean): List<SidebarNode> =
  if (truncated) listOf(MessageNode(TRUNCATED_MESSAGE)) else emptyList()

/**
 * The single message shown for a non-item outcome (design §12, texts verbatim). Total: an item outcome renders its
 * items and never asks (the caller's `when` is exhaustive), but should one ever ask it gets the generic failure text
 * rather than an exception thrown into the UI-thread compose.
 */
internal fun queryOutcomeMessage(outcome: QueryOutcome): String =
  when (outcome) {
    is QueryOutcome.Issues, is QueryOutcome.MergeRequests -> LOAD_FAILED_MESSAGE
    QueryOutcome.NeedsProject -> "Open a file from a GitLab project to run this query."
    QueryOutcome.Unsupported -> "This query type is not supported in Eclipse yet."
    is QueryOutcome.Invalid -> INVALID_PREFIX + outcome.message
    is QueryOutcome.Failed -> failureMessage(outcome)
  }

private fun failureMessage(failed: QueryOutcome.Failed): String =
  when (failed.kind) {
    QueryFailureKind.REJECTED -> "GitLab rejected this query. Check its filters."
    QueryFailureKind.UNAUTHORIZED -> "Not authorized. Check your GitLab token."
    QueryFailureKind.NOT_FOUND_OR_DENIED -> "Project not found or access denied."
    QueryFailureKind.RATE_LIMITED -> "GitLab rate limit reached. Try Refresh later."
    QueryFailureKind.TIMED_OUT -> "Timed out loading results. Try Refresh."
    QueryFailureKind.CURRENT_USER -> "Could not resolve the current user."
    QueryFailureKind.CONFIG -> failed.configMessage ?: LOAD_FAILED_MESSAGE
    QueryFailureKind.GENERIC -> LOAD_FAILED_MESSAGE
  }

/**
 * Builds the query roots of one [QueriesSlot] in config order (design §7), replacing the former hard-coded
 * "assigned to me" roots. Pure and SWT-free; UI thread only (its memo is unsynchronized).
 *
 * Per-query memo: a root is reused — same [QueryRootNode] instance, so JFace keeps its subtree's expansion — while
 * its id, its outcome slot (identity of the settled value; [Slot.Pending] is a singleton), its query object's JSON
 * (identity) and the mode are unchanged. An unreadable config yields one `__unreadable` root, an empty custom config
 * one `__empty` root. [reset] drops the memo (the coordinator calls it on a generation change).
 */
class QueryRootsComposer(private val viewModel: SidebarViewModel) {
  private class Entry(val outcomeKey: Any?, val source: Any, val mode: SidebarViewMode?, val node: QueryRootNode) {
    fun matches(outcomeKey: Any?, source: Any, mode: SidebarViewMode?): Boolean =
      this.outcomeKey === outcomeKey && this.source === source && this.mode == mode
  }

  private val memo = HashMap<String, Entry>()

  fun reset() {
    memo.clear()
  }

  fun compose(slot: QueriesSlot, mode: SidebarViewMode): List<SidebarNode> {
    val config = slot.config
    val roots =
      when {
        config is QueryConfig.Unreadable -> listOf(configRoot(UNREADABLE_ROOT_ID, config, UNREADABLE_MESSAGE))
        slot.queries.isEmpty() -> listOf(configRoot(EMPTY_ROOT_ID, config, EMPTY_MESSAGE))
        else -> slot.queries.map { query -> queryRoot(query, slot.outcomes[query.id] ?: Slot.Pending, mode) }
      }
    memo.keys.retainAll(roots.map { it.queryId }.toSet())
    return roots
  }

  private fun queryRoot(query: CustomQuery, outcome: Slot<QueryOutcome>, mode: SidebarViewMode): QueryRootNode =
    memoized(query.id, outcomeKey(outcome), query.json, mode) { viewModel.buildQueryRoot(query, outcome, mode) }

  private fun configRoot(id: String, config: QueryConfig, message: String): QueryRootNode =
    memoized(id, null, config, null) { QueryRootNode(id, CONFIG_ROOT_LABEL, listOf(MessageNode(message))) }

  private fun memoized(
    id: String,
    outcomeKey: Any?,
    source: Any,
    mode: SidebarViewMode?,
    build: () -> QueryRootNode,
  ): QueryRootNode {
    memo[id]?.takeIf { it.matches(outcomeKey, source, mode) }?.let { return it.node }
    return build().also { memo[id] = Entry(outcomeKey, source, mode, it) }
  }

  /** A pending slot keys itself (singleton); a settled one keys by its value, so re-wrapping counts as unchanged. */
  private fun outcomeKey(outcome: Slot<QueryOutcome>): Any =
    when (outcome) {
      is Slot.Pending -> outcome
      is Slot.Settled -> outcome.value
    }
}

/**
 * The [com.gitlab.eclipse.views.sidebar.DiscussionsSectionNode.nodeId]s under every [QueryRootNode] instance of
 * [oldInput] that is absent (by identity) from [newInput] — the discussion-registry keys a re-compose orphaned (design
 * §8, F10). A root the memo kept is the same instance, so its keys survive; any other top-level node is ignored.
 * Pure; UI thread like its caller.
 */
internal fun staleQueryRootSectionIds(oldInput: List<SidebarNode>, newInput: List<SidebarNode>): Set<Long> {
  val kept = newInput.filterIsInstance<QueryRootNode>()
  val gone = oldInput.filterIsInstance<QueryRootNode>().filter { old -> kept.none { it === old } }
  return collectDiscussionSectionNodeIds(gone)
}
