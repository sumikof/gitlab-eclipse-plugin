package com.gitlab.eclipse.mergerequests.discussions

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.GraphQlException
import com.gitlab.eclipse.ci.actions.normalizeInstanceUrl
import com.gitlab.eclipse.ci.actions.pinnedConnectionFor
import kotlinx.coroutines.CancellationException

/**
 * The security boundary of one discussion write (design §8.2, §15.3): connection gate first,
 * pre-send lifecycle re-check second, and only then the actual mutation — in exactly that order,
 * with nothing (no request building, no identifier resolution, no logging) between the checks
 * and the send.
 *
 * 1. **Connection gate** —
 *    [pinnedConnectionFor][com.gitlab.eclipse.ci.actions.pinnedConnectionFor] atomically captures
 *    the CURRENT connection and compares it against the instance url + auth fingerprint the MR
 *    node was loaded under. A mismatched instance, a changed credential on the same instance, or
 *    an unstable (mid-update) configuration returns [DiscussionWriteOutcome.GateRejected] with
 *    ZERO HTTP traffic and zero [mutate] calls: the gate is what prevents instance A's token from
 *    being transmitted to instance B, or an operation loaded under account A from executing as
 *    account B.
 * 2. **Pre-send lifecycle re-check** — the lifecycle is re-validated AFTER the gate, not before,
 *    because [GitLabApiClient.captureConnection] performs a configuration capture that can block:
 *    a plugin stop→restart can happen *during* it, and if the connection is unchanged the gate
 *    still passes. Re-checking [registryActive] / [registryEpoch] / [isActive] afterwards is what
 *    prevents a write issued before the stop from landing in the new lifecycle, where it would
 *    succeed on the server while every piece of completion UI is discarded. A failed re-check
 *    returns [DiscussionWriteOutcome.Aborted] with zero [mutate] calls.
 * 3. **Send** — [mutate] runs with the pinned snapshot from step 1 (never a re-captured one).
 *    `kotlinx.coroutines.CancellationException` is rethrown — [classifyWriteFailure] has no
 *    branch for it, so classifying it would turn a user's cancellation into
 *    [DiscussionWriteOutcome.Ambiguous]. Every other [Throwable] is classified by
 *    [classifyWriteFailure].
 *
 * [DiscussionWriteOutcome.GateRejected] and [DiscussionWriteOutcome.Aborted] stay distinct:
 * downstream, GateRejected shows a notification plus a text-preserving dialog (the user's typed
 * comment must survive), while Aborted deliberately produces no UI at all.
 *
 * [startEpoch] is the [DiscussionGenerationRegistry.currentEpoch] value captured when the write
 * was initiated on the UI thread. This function does NO logging; the caller decides what to log
 * (see [discussionAuditMessage]).
 */
fun runDiscussionWrite(
  apiClient: GitLabApiClient,
  nodeInstanceUrl: String,
  nodeAuthFingerprint: String,
  startEpoch: Long,
  isActive: () -> Boolean = { true },
  registryActive: () -> Boolean = { DiscussionGenerationRegistry.active },
  registryEpoch: () -> Long = { DiscussionGenerationRegistry.currentEpoch },
  mutate: (ConnectionSnapshot) -> Unit,
): DiscussionWriteOutcome {
  val connection = pinnedConnectionFor(apiClient, nodeInstanceUrl, nodeAuthFingerprint)
    ?: return DiscussionWriteOutcome.GateRejected
  if (!registryActive() || registryEpoch() != startEpoch || !isActive()) {
    return DiscussionWriteOutcome.Aborted
  }
  return try {
    mutate(connection)
    DiscussionWriteOutcome.Success
  } catch (e: CancellationException) {
    throw e
  } catch (e: Throwable) {
    classifyWriteFailure(e)
  }
}

/**
 * One structured, secret-free audit line for a discussion write (design §16). Carries the action,
 * the normalized instance url, project id, MR iid, target kind, and the outcome name; for
 * [DiscussionWriteOutcome.Definite] / [DiscussionWriteOutcome.Ambiguous] it adds
 * `exceptionType=<simple class name>` plus, when available, the safe metadata fields
 * `httpStatus` ([GitLabApiException.statusCode]), `hasDataKey` ([GraphQlException.hasDataKey]),
 * and `correlationId`.
 *
 * The load-bearing property is what the line must NEVER contain: the comment body, the target id,
 * the token, `cause.message` ([GraphQlException]'s message interpolates the server's error
 * strings, which can echo the submitted comment body), [GitLabApiException.responseBody], or the
 * exception object itself (no `toString()`, no logger attachment — a Phase 4 leak carried
 * `Bearer <token>` into the Eclipse Error Log that way). The target id is deliberately excluded:
 * `projectId` + `mrIid` + `targetKind` already locate the operation, and excluding it keeps the
 * line free of anything user-authored.
 *
 * [targetKind] is one of `"discussion"` / `"note"` / `"mergeRequest"`, matching
 * [DiscussionWriteKey.targetKind].
 */
fun discussionAuditMessage(
  action: String,
  instanceUrl: String,
  projectId: Long,
  mrIid: Long,
  targetKind: String,
  outcome: DiscussionWriteOutcome,
): String = buildString {
  append("discussionWrite action=").append(action)
  append(" instanceUrl=").append(normalizeInstanceUrl(instanceUrl))
  append(" projectId=").append(projectId)
  append(" mrIid=").append(mrIid)
  append(" targetKind=").append(targetKind)
  when (outcome) {
    DiscussionWriteOutcome.Success -> append(" outcome=success")
    DiscussionWriteOutcome.GateRejected -> append(" outcome=gateRejected")
    DiscussionWriteOutcome.Aborted -> append(" outcome=aborted")
    is DiscussionWriteOutcome.Definite -> {
      append(" outcome=definite")
      appendSafeCauseFields(outcome.cause)
    }
    is DiscussionWriteOutcome.Ambiguous -> {
      append(" outcome=ambiguous")
      appendSafeCauseFields(outcome.cause)
    }
  }
}

/**
 * Appends ONLY the vetted, secret-free fields of [cause]: its simple class name and, per type,
 * `httpStatus` / `hasDataKey` / `correlationId`. Never the message, response body, or the
 * exception object — see [discussionAuditMessage].
 */
private fun StringBuilder.appendSafeCauseFields(cause: Throwable) {
  append(" exceptionType=").append(cause.javaClass.simpleName)
  when (cause) {
    is GitLabApiException -> {
      append(" httpStatus=").append(cause.statusCode)
      cause.correlationId?.let { append(" correlationId=").append(it) }
    }
    is GraphQlException -> {
      append(" hasDataKey=").append(cause.hasDataKey)
      cause.correlationId?.let { append(" correlationId=").append(it) }
    }
    else -> Unit
  }
}
