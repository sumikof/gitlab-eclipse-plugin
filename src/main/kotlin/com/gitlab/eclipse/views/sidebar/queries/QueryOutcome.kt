package com.gitlab.eclipse.views.sidebar.queries

import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.GitLabApiTimeoutException
import com.gitlab.eclipse.api.GitLabConfigurationException
import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.views.issues.configErrorMessage

/** Why a query failed; a kind token only, safe to log (design §7, §11, §12). */
enum class QueryFailureKind {
  REJECTED,
  UNAUTHORIZED,
  NOT_FOUND_OR_DENIED,
  RATE_LIMITED,
  TIMED_OUT,
  CONFIG,
  GENERIC,
  CURRENT_USER,
}

/** How one query settled (design §7, §12). */
sealed interface QueryOutcome {
  class Issues(val items: List<GitLabIssue>, val truncated: Boolean) : QueryOutcome

  class MergeRequests(val items: List<GitLabMergeRequest>, val truncated: Boolean) : QueryOutcome

  object NeedsProject : QueryOutcome

  object Unsupported : QueryOutcome

  /** [message] is the validator problem's message; it names the field only, never the query's values. */
  class Invalid(val message: String) : QueryOutcome

  /** [configMessage] is the user-safe text of a [QueryFailureKind.CONFIG] failure, else null. */
  class Failed(val kind: QueryFailureKind, val configMessage: String? = null) : QueryOutcome
}

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_TOO_MANY_REQUESTS = 429

/** Maps a query's failure onto its display kind (design §7). */
fun classifyFailure(e: Throwable): QueryOutcome.Failed =
  when (e) {
    is GitLabApiException -> QueryOutcome.Failed(statusKind(e.statusCode))
    is GitLabApiTimeoutException -> QueryOutcome.Failed(QueryFailureKind.TIMED_OUT)
    is GitLabConfigurationException -> QueryOutcome.Failed(QueryFailureKind.CONFIG, configErrorMessage(e))
    else -> QueryOutcome.Failed(QueryFailureKind.GENERIC)
  }

private fun statusKind(status: Int): QueryFailureKind =
  when (status) {
    HTTP_BAD_REQUEST -> QueryFailureKind.REJECTED
    HTTP_UNAUTHORIZED -> QueryFailureKind.UNAUTHORIZED
    HTTP_FORBIDDEN, HTTP_NOT_FOUND -> QueryFailureKind.NOT_FOUND_OR_DENIED
    HTTP_TOO_MANY_REQUESTS -> QueryFailureKind.RATE_LIMITED
    else -> QueryFailureKind.GENERIC
  }
