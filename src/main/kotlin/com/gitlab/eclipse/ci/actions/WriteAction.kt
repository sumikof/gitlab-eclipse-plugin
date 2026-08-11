// The file groups the write-action cores (WriteOutcome + the top-level functions); the file
// name reflects the feature, not the single type.
@file:Suppress("MatchingDeclarationName")

package com.gitlab.eclipse.ci.actions

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.PostResult
import com.gitlab.eclipse.api.UnstableConnectionException
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.http.HttpTimeoutException

/**
 * SWT-free cores of the CI write handlers (design §8.5-§8.7): outcome classification,
 * connection validation + pinning, and the token-free structured audit line. Kept free of any
 * Eclipse/SWT dependency so the security and behavior invariants are headless-testable.
 */
sealed interface WriteOutcome {
  data class Success(val result: PostResult) : WriteOutcome

  /** [failureKind] is "http" | "timeout" | "io"; status/correlationId only exist for "http". */
  data class Failure(val httpStatus: Int?, val correlationId: String?, val failureKind: String) : WriteOutcome
}

/** Trailing-slash normalization so `https://x/` and `https://x` name the same instance. */
fun normalizeInstanceUrl(url: String): String = url.trimEnd('/')

/**
 * Runs [call] and classifies the result: success → [WriteOutcome.Success],
 * [GitLabApiException] → Failure("http") with status + correlation id,
 * [HttpTimeoutException] → Failure("timeout"), [IOException] → Failure("io").
 * [CancellationException] is rethrown — cancellation must propagate, never become a Failure.
 */
fun classifyWrite(call: () -> PostResult): WriteOutcome =
  try {
    WriteOutcome.Success(call())
  } catch (e: CancellationException) {
    throw e
  } catch (e: GitLabApiException) {
    WriteOutcome.Failure(e.statusCode, e.correlationId, "http")
  } catch (ignored: HttpTimeoutException) {
    WriteOutcome.Failure(null, null, "timeout")
  } catch (ignored: IOException) {
    WriteOutcome.Failure(null, null, "io")
  }

/**
 * Validates that the connection a sidebar node was loaded over is still the CURRENT connection,
 * and returns the snapshot to pin the write to — or null when the write must not run.
 *
 * ONE atomic [GitLabApiClient.captureConnectionIf] provides both the comparison value and the
 * pinned snapshot (no mixed-snapshot window; design §8.7-2b, closes 6A/7B/10B). The instance-url
 * comparison is handed to the capture as a predicate so it runs INSIDE the seqlock, before the
 * credential is read: a changed instance url (FR-8) rejects the gate without ever touching the
 * token manager (#49). A changed credential on the same url (9A) still rejects here, after the
 * capture — the fingerprint is derived from the token, so that case cannot be decided earlier.
 * An unstable connection ([UnstableConnectionException] → null). A null return means the caller
 * notifies the user and issues NO write.
 */
fun pinnedConnectionFor(
  apiClient: GitLabApiClient,
  nodeInstanceUrl: String,
  nodeAuthFingerprint: String,
): ConnectionSnapshot? {
  val snapshot = try {
    // A url mismatch yields null with the credentials untouched (#49).
    apiClient.captureConnectionIf { url -> sameConfiguredInstance(nodeInstanceUrl, url) }
  } catch (ignored: UnstableConnectionException) {
    return null
  } ?: return null
  // The url match is already guaranteed by the capture-time predicate; kept as a postcondition so
  // the invariant stays locally readable at the point the snapshot is returned.
  val sameInstance = sameConfiguredInstance(nodeInstanceUrl, snapshot.instanceUrl)
  val sameAccount = snapshot.authFingerprint == nodeAuthFingerprint
  return if (sameInstance && sameAccount) snapshot else null
}

/**
 * One structured audit line for the Eclipse Error Log (AC-8). Always carries the normalized
 * instance url, action, project id, and target — even when the failure has no HTTP status or
 * correlation id (timeout/io). Never contains a token or a raw response body.
 */
fun writeAuditMessage(
  action: String,
  instanceUrl: String,
  projectId: Long,
  targetKind: String,
  targetId: Long,
  outcome: WriteOutcome,
): String = buildString {
  append("ciWriteAction action=").append(action)
  append(" instanceUrl=").append(normalizeInstanceUrl(instanceUrl))
  append(" projectId=").append(projectId)
  append(" targetKind=").append(targetKind)
  append(" targetId=").append(targetId)
  when (outcome) {
    is WriteOutcome.Success -> {
      append(" outcome=success httpStatus=").append(outcome.result.httpStatus)
      outcome.result.correlationId?.let { append(" correlationId=").append(it) }
    }
    is WriteOutcome.Failure -> {
      append(" outcome=failure failureKind=").append(outcome.failureKind)
      outcome.httpStatus?.let { append(" httpStatus=").append(it) }
      outcome.correlationId?.let { append(" correlationId=").append(it) }
    }
  }
}
