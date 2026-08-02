// The file groups the SWT-free CI lint cores (key + outcome + orchestration + audit); the file
// name reflects the feature, not a single type.
@file:Suppress("MatchingDeclarationName")

package com.gitlab.eclipse.ci.lint

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.UnstableConnectionException
import com.gitlab.eclipse.api.model.CiLintResult
import com.gitlab.eclipse.ci.actions.WriteOutcome
import com.gitlab.eclipse.ci.actions.normalizeInstanceUrl
import com.gitlab.eclipse.ci.actions.sameConfiguredInstance
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.http.HttpTimeoutException

/**
 * In-flight identity of one CI lint (design §8.7). [sourceId] is the stable source identity
 * supplied by the caller (e.g. the editor/document identity) so two lints of the same
 * (instance, project, command) but different in-memory sources never collide.
 */
data class CiLintKey(
  val command: String,
  val instanceUrl: String,
  val projectId: String,
  val sourceId: String,
)

/** Result of the SWT-free CI lint orchestration; the handler maps each case to a UI effect. */
sealed interface CiLintOutcome {
  data class Linted(val result: CiLintResult) : CiLintOutcome
  object ConnectionUnstable : CiLintOutcome
  object InstanceMismatch : CiLintOutcome
  data class Failed(val outcome: WriteOutcome.Failure) : CiLintOutcome
}

/**
 * SWT-free CI lint orchestration (design §8.4), mirroring [com.gitlab.eclipse.ci.actions.runCreatePipeline]:
 * capture → same-instance gate → lint, with the gate provably BEFORE the lint call. [capture]
 * returns the pinned snapshot (or throws [UnstableConnectionException]); [lint] performs the
 * lint call. [lint] is NEVER invoked when the gate fails or the connection is unstable, so a
 * mismatch never sends the yaml to the wrong instance.
 *
 * Unlike [com.gitlab.eclipse.ci.actions.classifyWrite] (which is `() -> PostResult`-specific),
 * the classification here is inlined because the success payload is [CiLintResult]. A thrown
 * [CancellationException] propagates (it never becomes a Failed).
 */
fun runCiLint(
  contextInstanceUrl: String,
  capture: () -> ConnectionSnapshot,
  lint: (ConnectionSnapshot) -> CiLintResult,
): CiLintOutcome {
  val connection = try {
    capture()
  } catch (ignored: UnstableConnectionException) {
    return CiLintOutcome.ConnectionUnstable
  }
  if (!sameConfiguredInstance(contextInstanceUrl, connection.instanceUrl)) {
    return CiLintOutcome.InstanceMismatch
  }
  return classifyLint { lint(connection) }
}

private fun classifyLint(call: () -> CiLintResult): CiLintOutcome =
  try {
    CiLintOutcome.Linted(call())
  } catch (e: CancellationException) {
    throw e
  } catch (e: GitLabApiException) {
    CiLintOutcome.Failed(WriteOutcome.Failure(e.statusCode, e.correlationId, "http"))
  } catch (ignored: HttpTimeoutException) {
    CiLintOutcome.Failed(WriteOutcome.Failure(null, null, "timeout"))
  } catch (ignored: IOException) {
    CiLintOutcome.Failed(WriteOutcome.Failure(null, null, "io"))
  }

/**
 * One token-free structured audit line for CI lint (design §8.4/§18). Carries command/instance
 * /project plus the outcome; never contains a token, response body, yaml body, or errors body.
 * [CiLintOutcome.Linted] carries only presence (valid + whether a merged yaml exists) since
 * the lint call's success payload never carries an httpStatus.
 */
fun buildCiLintAuditMessage(
  instanceUrl: String,
  projectId: String,
  command: String,
  outcome: CiLintOutcome,
): String = buildString {
  append("ciLint command=").append(command)
  append(" instanceUrl=").append(normalizeInstanceUrl(instanceUrl))
  append(" projectId=").append(projectId)
  when (outcome) {
    is CiLintOutcome.Linted -> {
      val merged = if (outcome.result.mergedYaml != null) "present" else "absent"
      append(" outcome=success valid=").append(outcome.result.valid)
      append(" merged=").append(merged)
    }
    is CiLintOutcome.Failed -> {
      append(" outcome=failure failureKind=").append(outcome.outcome.failureKind)
      outcome.outcome.httpStatus?.let { append(" httpStatus=").append(it) }
      outcome.outcome.correlationId?.let { append(" correlationId=").append(it) }
    }
    CiLintOutcome.ConnectionUnstable -> append(" outcome=aborted reason=connection-unstable")
    CiLintOutcome.InstanceMismatch -> append(" outcome=aborted reason=instance-mismatch")
  }
}
