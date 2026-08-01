// The file groups the read-action cores (TraceResult + the top-level functions); the file
// name reflects the feature, not the single type.
@file:Suppress("MatchingDeclarationName")

package com.gitlab.eclipse.ci.joblog

import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.ci.actions.normalizeInstanceUrl
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.http.HttpTimeoutException

private const val HTTP_NOT_FOUND = 404

/**
 * SWT-free cores of the "Display Log" read (design §14/§16): outcome classification and the
 * token-free structured audit line. Kept free of any Eclipse/SWT dependency so the security
 * and behavior invariants are headless-testable.
 */
sealed interface TraceResult {
  data class Loaded(val text: String) : TraceResult

  /**
   * [kind] is "http" | "timeout" | "io"; status/correlationId only exist for "http".
   * [notCommittal] is true for 404 — the user message must not reveal whether the job exists
   * (existence-hiding, design §15: 403 is surfaced by the server as 404).
   */
  data class Failed(
    val kind: String,
    val httpStatus: Int?,
    val correlationId: String?,
    val notCommittal: Boolean,
  ) : TraceResult
}

/**
 * Runs [getRaw] (the trace GET) then [strip] (formatting) and classifies the outcome — the READ
 * analog of classifyWrite. [CancellationException] is RETHROWN, never a Failure. Any OTHER
 * unexpected throwable is deliberately NOT caught here: it propagates to the handler's terminal
 * catch so the "unexpected" audit stays in one place.
 */
fun fetchAndFormatTrace(getRaw: () -> String, strip: (String) -> String): TraceResult =
  try {
    TraceResult.Loaded(strip(getRaw()))
  } catch (e: CancellationException) {
    throw e
  } catch (e: GitLabApiException) {
    TraceResult.Failed("http", e.statusCode, e.correlationId, notCommittal = e.statusCode == HTTP_NOT_FOUND)
  } catch (ignored: HttpTimeoutException) {
    TraceResult.Failed("timeout", null, null, notCommittal = false)
  } catch (ignored: IOException) {
    TraceResult.Failed("io", null, null, notCommittal = false)
  }

/**
 * One structured audit line for the Eclipse Error Log — the READ analog of writeAuditMessage
 * (design §16). NEVER contains a token or response body (it has no parameter that could carry
 * one). [outcome] is a short read-verb tag (e.g. "connectionRejected", "http", "timeout",
 * "io", "unexpected"); http status + correlation id are appended only when present.
 */
fun readAuditMessage(
  action: String,
  instanceUrl: String,
  projectId: Long?,
  jobId: Long,
  outcome: String,
  httpStatus: Int? = null,
  correlationId: String? = null,
): String = buildString {
  append("ciReadAction action=").append(action)
  append(" instanceUrl=").append(normalizeInstanceUrl(instanceUrl))
  append(" projectId=").append(projectId)
  append(" jobId=").append(jobId)
  append(" outcome=").append(outcome)
  httpStatus?.let { append(" httpStatus=").append(it) }
  correlationId?.let { append(" correlationId=").append(it) }
}
