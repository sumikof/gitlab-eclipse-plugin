package com.gitlab.eclipse.views.sidebar.queries

import com.gitlab.eclipse.api.ConnectionSnapshot
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * The IO body of one queries run (design §8, F1/F7/F8), kept SWT-free so it is testable headless. The view launches
 * it inside the queries job and turns each settled outcome into a UI-thread apply.
 *
 * Never throws except [CancellationException]: a capture failure settles every runnable query with that failure and
 * the others with their own outcome; any other failure settles every query of this run that has not settled yet with
 * [QueryFailureKind.GENERIC], so no root is left showing "Loading…" (unless the run is no longer active — then a newer
 * run owns the roots). WARN lines carry `exceptionType=` only (design §11).
 */
class CustomQueriesRefresh(
  private val runner: CustomQueryRunner,
  private val captureConnection: () -> ConnectionSnapshot,
  private val log: (String) -> Unit,
) {
  /**
   * Runs [queries] minus those [reuse] reports as settled. [resolveProject] (blocking JGit) is called only when a
   * runnable query targets the project. [onSettled] is called at most once per query, on any thread.
   */
  suspend fun run(
    queries: List<CustomQuery>,
    reuse: QueryReuse,
    resolveProject: () -> QueryProject?,
    isActive: () -> Boolean,
    onSettled: (queryId: String, outcome: QueryOutcome, executed: QueryPlan.Run?) -> Unit,
  ) {
    val toRun = queries.filterNot { reuse.isSettled(it.id) }
    if (toRun.isEmpty()) return
    val done = ConcurrentHashMap.newKeySet<String>()
    val settle: (String, QueryOutcome, QueryPlan.Run?) -> Unit = { id, outcome, executed ->
      if (done.add(id)) onSettled(id, outcome, executed)
    }
    try {
      val project = if (toRun.any(::runsInProject)) projectOrNull(resolveProject) else null
      val connection = try {
        captureConnection()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log("Sidebar queries could not capture the connection: exceptionType=${e.javaClass.name}")
        if (isActive()) settleWithoutConnection(toRun, project, classifyFailure(e), settle)
        return
      }
      runner.run(queries, project, connection, isActive, reuse, settle)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      // Throwable, not Exception: an Error (e.g. StackOverflowError) must not strand the roots nor reach the scope.
      log("Sidebar queries failed: exceptionType=${e.javaClass.name}")
      if (isActive()) {
        toRun.filterNot { it.id in done }.forEach { query ->
          runCatching { settle(query.id, QueryOutcome.Failed(QueryFailureKind.GENERIC), null) }
        }
      }
    }
  }

  private fun projectOrNull(resolveProject: () -> QueryProject?): QueryProject? =
    try {
      resolveProject()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log("Sidebar queries could not resolve the repository: exceptionType=${e.javaClass.name}")
      null
    }

  /** Fix of the capture-failure path: only a query that would send a request gets [failure] (design F8). */
  private fun settleWithoutConnection(
    queries: List<CustomQuery>,
    project: QueryProject?,
    failure: QueryOutcome,
    settle: (String, QueryOutcome, QueryPlan.Run?) -> Unit,
  ) {
    queries.forEach { query ->
      val plan = runCatching { CustomQueryRequestBuilder.plan(query, project, CURRENT_USER_PLACEHOLDER) }.getOrNull()
      val outcome = when (plan) {
        null -> QueryOutcome.Failed(QueryFailureKind.GENERIC)
        is QueryPlan.Run -> failure
        else -> notRunnableOutcome(plan)
      }
      settle(query.id, outcome, null)
    }
  }
}

/** Probe project: lets [runsInProject] ask the pure planner whether a query would run were a project resolved. */
private val PROBE_PROJECT = QueryProject("probe")

/** True when [query] would send a project-scoped request once a project is resolved (design F7). Pure. */
private fun runsInProject(query: CustomQuery): Boolean =
  CustomQueryValidator.effectiveTarget(query) == QueryTarget.PROJECT &&
    runCatching { CustomQueryRequestBuilder.plan(query, PROBE_PROJECT, CURRENT_USER_PLACEHOLDER) }
      .getOrNull() is QueryPlan.Run
