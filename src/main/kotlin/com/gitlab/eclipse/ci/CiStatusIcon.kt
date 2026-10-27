package com.gitlab.eclipse.ci

/** Icon kind a CI status is drawn with in the sidebar; [iconName] is the shared icon registry name. */
enum class CiStatusIcon(val iconName: String) {
  SUCCESS("pipeline_success"),
  FAILED("pipeline_failed"),
  FAILED_ALLOWED("pipeline_warning"),
  RUNNING("pipeline_running"),
  PENDING("pipeline_pending"),
  CANCELED("pipeline_canceled"),
  SKIPPED("pipeline_skipped"),
  MANUAL("pipeline_manual"),
  UNKNOWN("pipeline_unknown"),
}

/**
 * Maps a raw GitLab CI [status] to its [CiStatusIcon]. [allowFailure] only affects `failed`; matching is
 * case-sensitive like [CiStatus], and anything unrecognised (or null) is [CiStatusIcon.UNKNOWN].
 */
fun ciStatusIcon(status: String?, allowFailure: Boolean = false): CiStatusIcon =
  when (status) {
    "success" -> CiStatusIcon.SUCCESS
    "failed" -> if (allowFailure) CiStatusIcon.FAILED_ALLOWED else CiStatusIcon.FAILED
    "running" -> CiStatusIcon.RUNNING
    "pending", "created", "preparing", "waiting_for_resource", "scheduled" -> CiStatusIcon.PENDING
    "canceled", "canceling" -> CiStatusIcon.CANCELED
    "skipped" -> CiStatusIcon.SKIPPED
    "manual" -> CiStatusIcon.MANUAL
    else -> CiStatusIcon.UNKNOWN
  }
