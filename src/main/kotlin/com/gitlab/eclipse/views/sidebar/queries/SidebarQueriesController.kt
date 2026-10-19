package com.gitlab.eclipse.views.sidebar.queries

import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.views.sidebar.ApplyScope
import com.gitlab.eclipse.views.sidebar.QueriesSlot
import com.gitlab.eclipse.views.sidebar.RefreshSlots
import com.gitlab.eclipse.views.sidebar.Slot
import kotlinx.coroutines.CoroutineScope
import org.eclipse.jface.preference.IPreferenceStore
import java.io.File

/** The IO work of one queries run: [CustomQueriesRefresh.run]'s shape, so tests can substitute it. */
typealias QueriesWork = suspend (
  queries: List<CustomQuery>,
  reuse: QueryReuse,
  resolveProject: () -> QueryProject?,
  isActive: () -> Boolean,
  onSettled: (queryId: String, outcome: QueryOutcome, executed: QueryPlan.Run?) -> Unit,
) -> Unit

/**
 * The query side of the sidebar view (ISSUE-01 design §7-§9), SWT-free so its wiring is testable headless: it owns
 * the setting listener, the queries job ([QueriesJobLifecycle]) and the result cache, and works on the view's
 * [RefreshSlots] through ports. The view keeps the SWT parts — the [uiHop] (asyncExec with its disposed check) and
 * [apply] (its `applyCompose`) — and the current-branch job, which this class never sees.
 *
 * - The queries job is launched on [scope] itself: a sibling of the view's `fetchJob`, never its child (F1).
 * - [refreshQueries] only ever bumps [RefreshSlots.queriesEpoch] and swaps [RefreshSlots.queries]; it has no access to
 *   the generation, `currentBranchEpoch`, `fetchJob`, the CI-04 refresher or the MR caches.
 * - Every apply is scoped by [ApplyScope.Queries] with the epoch captured at launch and writes the captured
 *   [QueriesSlot] instance, so a superseded run's late settle changes nothing (neither slot nor cache).
 *
 * Threading: [start], [onFullRefresh], [refreshQueries] and [dispose] run on the UI thread; [work] runs in the job (IO)
 * and its settles reach the slots and the cache only inside [apply]'s update, which the view runs on the UI thread.
 * The store listener may fire on any thread and only calls [uiHop].
 */
class SidebarQueriesController(
  scope: CoroutineScope,
  store: IPreferenceStore,
  private val work: QueriesWork,
  private val resolveProject: (File?) -> QueryProject?,
  private val uiHop: (() -> Unit) -> Unit,
  private val apply: (slots: RefreshSlots, scope: ApplyScope, update: () -> Unit) -> Unit,
  private val currentSlots: () -> RefreshSlots?,
  private val activeEditorFile: () -> File?,
  private val fullRefresh: () -> Unit,
  private val log: (String) -> Unit,
) {
  private val readStore = { store.getString(PreferenceConstants.SIDEBAR_CUSTOM_QUERIES) }
  private val lifecycle = QueriesJobLifecycle(scope, log)
  private val listener = CustomQueriesPreferenceListener(store) { uiHop { refreshQueries() } }

  // UI thread only: written inside apply's update, read by refreshQueries, cleared by every full refresh.
  private val cache = CustomQueryResultCache()
  private var disposed = false

  /** UI thread, once the view's widgets exist. Idempotent. */
  fun start() {
    listener.register()
  }

  /**
   * UI thread, from the view's full refresh with the new generation's [slots]: decodes the setting into a fresh
   * [QueriesSlot], drops every cached result and re-runs ALL queries (no reuse), replacing the previous queries job.
   * With no query to run (an empty list or an unreadable setting) no settle will ever compose the queries section, so
   * one queries apply is requested here instead.
   */
  fun onFullRefresh(slots: RefreshSlots, activeEditorFile: File?) {
    if (disposed) return
    slots.queries = QueriesSlot(readConfig())
    cache.clear()
    launch(slots, QueryReuse.None, activeEditorFile)
    if (slots.queries.queries.isEmpty()) apply(slots, ApplyScope.Queries(slots.queriesEpoch)) {}
  }

  /**
   * UI thread. The setting changed (design §8): within the live generation, bumps [RefreshSlots.queriesEpoch], swaps
   * in a new [QueriesSlot] whose unchanged queries keep their instances ([carryOverUnchanged]) and whose stage (a)
   * outcomes are settled at once, re-runs the rest (stage (b) is decided in the job) and composes at once. A view that
   * never refreshed runs its first full refresh instead.
   */
  fun refreshQueries() {
    if (disposed) return
    val slots = currentSlots()
    if (slots == null) {
      fullRefresh()
      return
    }
    val previous = slots.queries
    val config = readConfig()
    val queries = QueriesSlot(config, carryOverUnchanged(previous.queries, config.queries))
    val reuse = cache.reuseFor(queries.queries, previous.outcomes)
    reuse.settled.forEach { (id, outcome) -> queries.outcomes[id] = Slot.Settled(outcome) }
    cache.retainOnly(queries.queries.mapTo(HashSet()) { it.id })
    slots.queriesEpoch++
    slots.queries = queries
    launch(slots, reuse, activeEditorFile())
    apply(slots, ApplyScope.Queries(slots.queriesEpoch)) {}
  }

  /** UI thread. Removes the store listener and cancels the queries job; afterwards nothing runs. Idempotent. */
  fun dispose() {
    disposed = true
    listener.dispose()
    lifecycle.dispose()
  }

  /**
   * Replaces the queries job with a run of [slots]' current [QueriesSlot] under the current epoch, both captured
   * here. The repository is resolved inside the job (blocking JGit) with the editor file captured on the UI thread.
   */
  private fun launch(slots: RefreshSlots, reuse: QueryReuse, editorFile: File?) {
    val queries = slots.queries
    val epoch = slots.queriesEpoch
    val configPrints = queries.queries.associate { it.id to QueryFingerprints.config(it) }
    val project = { resolveProject(editorFile) }
    lifecycle.launch { isActive ->
      work(queries.queries, reuse, project, isActive) { id, outcome, executed ->
        apply(slots, ApplyScope.Queries(epoch)) {
          queries.outcomes[id] = Slot.Settled(outcome)
          configPrints[id]?.let { cache.put(id, it, executed?.let(QueryFingerprints::request), outcome) }
        }
      }
    }
  }

  /** Decodes the setting; an unreadable value is logged once per call with its reason token only (design §11). */
  private fun readConfig(): QueryConfig {
    val config = CustomQueryCodec.decode(readStore())
    if (config is QueryConfig.Unreadable) log("Sidebar queries setting unreadable: reason=${config.reason}")
    return config
  }
}
