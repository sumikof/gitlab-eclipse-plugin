package com.gitlab.eclipse.views.sidebar.queries

import com.gitlab.eclipse.api.ApiRequest
import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.CurrentUserService
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

private const val DEFAULT_PARALLELISM = 4
private const val UNKNOWN_TYPE = "unknown"
private const val ABSENT = "-"

/** Stands in for a plan when building one threw unexpectedly; the query settles as Failed(GENERIC). */
private val UNPLANNED =
  QueryPlan.Invalid(QueryProblem(ProblemSeverity.ERROR, "query", "Query could not be prepared."))

/**
 * Runs custom queries in isolation (design §7): one query's failure never affects another, at most [parallelism]
 * are in flight, `<current_user>` is resolved at most once and only when a runnable query uses it. Network work runs
 * on [dispatcher]. Failures are logged through [log] (WARN) with safe tokens only (design §11).
 */
class CustomQueryRunner(
  private val apiClient: GitLabApiClient,
  private val currentUserService: CurrentUserService,
  private val log: (String) -> Unit,
  private val parallelism: Int = DEFAULT_PARALLELISM,
  private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  /**
   * Runs [queries]; calls [onSettled] (any thread) once per query as it settles; returns when all settled. Throws
   * [CancellationException] once [isActive] turns false or the caller is cancelled; nothing settles after that.
   */
  suspend fun run(
    queries: List<CustomQuery>,
    project: QueryProject?,
    connection: ConnectionSnapshot,
    isActive: () -> Boolean,
    onSettled: (queryId: String, outcome: QueryOutcome, plan: QueryPlan) -> Unit,
  ) {
    val semaphore = Semaphore(parallelism)
    val run = Run(project, connection, isActive)
    coroutineScope {
      queries.mapIndexed { index, query ->
        async(dispatcher) {
          semaphore.withPermit {
            val (outcome, plan) = run.settle(index, query)
            currentCoroutineContext().ensureActive()
            if (!isActive()) throw CancellationException("Sidebar queries refresh is no longer active")
            onSettled(query.id, outcome, plan)
          }
        }
      }.awaitAll()
    }
  }

  /** One [run] call's shared state. */
  private inner class Run(
    private val project: QueryProject?,
    private val connection: ConnectionSnapshot,
    private val isActive: () -> Boolean,
  ) {
    private val userMutex = Mutex()
    private var currentUser: Result<String>? = null

    suspend fun settle(index: Int, query: CustomQuery): Pair<QueryOutcome, QueryPlan> {
      val draft = runCatching { CustomQueryRequestBuilder.plan(query, project, CURRENT_USER_PLACEHOLDER) }
        .getOrElse { return failed(index, query, it, UNPLANNED) }
      if (draft !is QueryPlan.Run) return notRunnable(draft) to draft
      val plan = if (CustomQueryRequestBuilder.usesCurrentUser(query)) {
        val username = currentUsername().getOrElse { return failed(index, query, it, draft, currentUser = true) }
        runCatching { CustomQueryRequestBuilder.plan(query, project, username) as QueryPlan.Run }
          .getOrElse { return failed(index, query, it, UNPLANNED) }
      } else {
        draft
      }
      return runCatching { fetch(query, plan) }.getOrElse { return failed(index, query, it, plan) } to plan
    }

    private fun notRunnable(plan: QueryPlan): QueryOutcome =
      when (plan) {
        is QueryPlan.Invalid -> QueryOutcome.Invalid(plan.problem.message)
        QueryPlan.NeedsProject -> QueryOutcome.NeedsProject
        else -> QueryOutcome.Unsupported
      }

    private fun fetch(query: CustomQuery, plan: QueryPlan.Run): QueryOutcome =
      if (CustomQueryValidator.effectiveType(query) == CustomQueryType.ISSUES) {
        val result = fetchPages(plan, GitLabIssue::class.java)
        QueryOutcome.Issues(result.items.distinctBy { it.id }, result.truncated)
      } else {
        val result = fetchPages(plan, GitLabMergeRequest::class.java)
        QueryOutcome.MergeRequests(result.items.distinctBy { it.id }, result.truncated)
      }

    private fun <T> fetchPages(plan: QueryPlan.Run, type: Class<T>) =
      apiClient.fetchListPages(ApiRequest(plan.path, plan.params, type), plan.maxItems, isActive, connection)

    private suspend fun currentUsername(): Result<String> =
      userMutex.withLock {
        currentUser ?: runCatching { currentUserService.getCurrentUser(connection).username }
          .onFailure { if (it is CancellationException) throw it }
          .also { currentUser = it }
      }

    private fun failed(
      index: Int,
      query: CustomQuery,
      e: Throwable,
      plan: QueryPlan,
      currentUser: Boolean = false,
    ): Pair<QueryOutcome, QueryPlan> {
      if (e is CancellationException) throw e
      val outcome = if (currentUser) QueryOutcome.Failed(QueryFailureKind.CURRENT_USER) else classifyFailure(e)
      warn(index, query, outcome.kind, e)
      return outcome to plan
    }
  }

  /** Design §11: position, type, kind, status, correlation id and exception class only — never the Throwable. */
  private fun warn(index: Int, query: CustomQuery, kind: QueryFailureKind, e: Throwable) {
    val type = CustomQueryValidator.effectiveType(query)?.takeIf { it.supported }?.wire ?: UNKNOWN_TYPE
    val api = e as? GitLabApiException
    log(
      "Sidebar query failed: position=${index + 1} type=$type kind=$kind status=${api?.statusCode ?: ABSENT} " +
        "correlationId=${api?.correlationId ?: ABSENT} exceptionType=${e.javaClass.name}",
    )
  }
}
