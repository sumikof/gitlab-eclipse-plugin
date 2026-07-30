package com.gitlab.eclipse.ci

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

  private fun metaFor(status: String?, allowFailure: Boolean): Meta =
    if (status == "failed" && allowFailure) FAILED_ALLOWED else STATUS_METADATA[status] ?: UNKNOWN

  /** Human-readable display name for [status], never throws (unknown/null -> "Status Unknown"). */
  fun displayName(status: String?, allowFailure: Boolean = false): String = metaFor(status, allowFailure).name

  /** Sort/severity priority for [status], never throws (unknown/null -> 0). */
  fun priority(status: String?, allowFailure: Boolean = false): Int = metaFor(status, allowFailure).priority
}
