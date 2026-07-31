package com.gitlab.eclipse.ci

/** Context-menu action a CI status affords, ported from `ci_status_metadata.ts`. */
enum class CiAction { RETRYABLE, CANCELLABLE, EXECUTABLE }

/** CI status -> display name + priority, ported from `ci_status_metadata.ts` (name + priority only; icon/contextAction excluded). */
@Suppress("MagicNumber")
object CiStatus {
  private data class Meta(val name: String, val priority: Int)

  private val UNKNOWN = Meta("Status Unknown", 0)
  private val FAILED_ALLOWED = Meta("Failed (allowed to fail)", 2)

  private val STATUS_METADATA: Map<String, Meta> = mapOf(
    "manual" to Meta("Manual", 0),
    "success" to Meta("Passed", 1),
    "created" to Meta("Created", 3),
    "waiting_for_resource" to Meta("Waiting for resource", 4),
    "preparing" to Meta("Preparing", 5),
    "pending" to Meta("Pending", 6),
    "scheduled" to Meta("Delayed", 7),
    "skipped" to Meta("Skipped", 8),
    "canceled" to Meta("Cancelled", 9),
    "canceling" to Meta("Cancelling", 10),
    "failed" to Meta("Failed", 11),
    "running" to Meta("Running", 12),
  )

  private val CONTEXT_ACTIONS: Map<String, CiAction?> = mapOf(
    "manual" to CiAction.EXECUTABLE,
    "success" to CiAction.RETRYABLE,
    "created" to CiAction.CANCELLABLE,
    "waiting_for_resource" to CiAction.CANCELLABLE,
    "preparing" to CiAction.CANCELLABLE,
    "pending" to CiAction.CANCELLABLE,
    "scheduled" to CiAction.CANCELLABLE,
    "skipped" to null,
    "canceled" to CiAction.RETRYABLE,
    "canceling" to CiAction.RETRYABLE,
    "failed" to CiAction.RETRYABLE,
    "running" to CiAction.CANCELLABLE,
  )

  private fun metaFor(status: String?, allowFailure: Boolean): Meta =
    if (status == "failed" && allowFailure) FAILED_ALLOWED else STATUS_METADATA[status] ?: UNKNOWN

  /** Human-readable display name for [status], never throws (unknown/null -> "Status Unknown"). */
  fun displayName(status: String?, allowFailure: Boolean = false): String = metaFor(status, allowFailure).name

  /** Sort/severity priority for [status], never throws (unknown/null -> 0). */
  fun priority(status: String?, allowFailure: Boolean = false): Int = metaFor(status, allowFailure).priority

  /**
   * Context-menu action for [status], never throws (unknown/null -> null).
   * [allowFailure] is accepted for signature symmetry with [displayName]/[priority] but has no
   * effect: failed stays RETRYABLE regardless.
   */
  @Suppress("UnusedParameter")
  fun contextAction(status: String?, allowFailure: Boolean = false): CiAction? = CONTEXT_ACTIONS[status]
}
