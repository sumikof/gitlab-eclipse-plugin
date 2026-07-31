// The file groups the SWT-free create cores (key + gate + orchestration + audit); the file
// name reflects the feature, not a single type.
@file:Suppress("MatchingDeclarationName")

package com.gitlab.eclipse.ci.actions

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.PostResult
import com.gitlab.eclipse.api.UnstableConnectionException

/**
 * In-flight identity of one create (design §9/§16, Codex P1). Create has no numeric target id,
 * so the key is (normalized instance url, encoded project id, ref) held verbatim — different
 * (project, ref) never collide, so concurrent creates on different targets are allowed while a
 * repeat of the SAME (instance, project, ref) is serialized.
 */
data class CreateWriteKey(val instanceUrl: String, val projectId: String, val ref: String)

/**
 * True when the repository's resolved instance equals the configured connection's instance
 * (design FR-8). Uses [normalizeInstanceUrl] so a trailing-slash difference does not falsely
 * reject. A false result MUST abort the create with NO write.
 */
fun sameConfiguredInstance(contextInstanceUrl: String, connectionInstanceUrl: String): Boolean =
  normalizeInstanceUrl(contextInstanceUrl) == normalizeInstanceUrl(connectionInstanceUrl)

/** Result of the SWT-free create orchestration; the handler maps each case to a UI effect. */
sealed interface CreateResult {
  data class Created(val result: PostResult) : CreateResult
  object ConnectionUnstable : CreateResult
  object InstanceMismatch : CreateResult
  data class Failed(val outcome: WriteOutcome.Failure) : CreateResult
}

/**
 * SWT-free create orchestration (design §8.5, Codex P1): capture → same-instance gate → POST,
 * with the gate provably BEFORE the POST. [capture] returns the pinned snapshot (or throws
 * [UnstableConnectionException]); [create] performs the POST. [create] is NEVER invoked when the
 * gate fails or the connection is unstable, so a mismatch issues no write. Exceptions from
 * [create] are classified via [classifyWrite]; a thrown [kotlinx.coroutines.CancellationException]
 * from within [classifyWrite] propagates (it never becomes a Failed).
 */
fun runCreatePipeline(
  contextInstanceUrl: String,
  capture: () -> ConnectionSnapshot,
  create: (ConnectionSnapshot) -> PostResult,
): CreateResult {
  val connection = try {
    capture()
  } catch (ignored: UnstableConnectionException) {
    return CreateResult.ConnectionUnstable
  }
  if (!sameConfiguredInstance(contextInstanceUrl, connection.instanceUrl)) {
    return CreateResult.InstanceMismatch
  }
  return when (val outcome = classifyWrite { create(connection) }) {
    is WriteOutcome.Success -> CreateResult.Created(outcome.result)
    is WriteOutcome.Failure -> CreateResult.Failed(outcome)
  }
}

/**
 * One token-free structured audit line for create (design §18). Carries action/instance/project
 * /ref plus the outcome; never contains a token or a raw response body. Separate from
 * [writeAuditMessage] (which is Long-id based for retry/cancel/play).
 */
fun buildCreateAuditMessage(
  instanceUrl: String,
  projectId: String,
  ref: String,
  outcome: CreateResult,
): String = buildString {
  append("ciWriteAction action=create")
  append(" instanceUrl=").append(normalizeInstanceUrl(instanceUrl))
  append(" projectId=").append(projectId)
  append(" ref=").append(ref)
  when (outcome) {
    is CreateResult.Created -> {
      append(" outcome=success httpStatus=").append(outcome.result.httpStatus)
      outcome.result.correlationId?.let { append(" correlationId=").append(it) }
    }
    is CreateResult.Failed -> {
      append(" outcome=failure failureKind=").append(outcome.outcome.failureKind)
      outcome.outcome.httpStatus?.let { append(" httpStatus=").append(it) }
      outcome.outcome.correlationId?.let { append(" correlationId=").append(it) }
    }
    CreateResult.ConnectionUnstable -> append(" outcome=aborted reason=connection-unstable")
    CreateResult.InstanceMismatch -> append(" outcome=aborted reason=instance-mismatch")
  }
}
