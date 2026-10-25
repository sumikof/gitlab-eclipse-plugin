package com.gitlab.eclipse.ci.joblog

private const val SHORT_WAIT_MS = 5_000L
private const val LONG_WAIT_MS = 15_000L

enum class JobStatusClass { LIVE, NOT_STARTED, TERMINAL }

/** Case-sensitive. Unknown values and null are TERMINAL (the safe side: no endless polling). */
fun classifyJobStatus(status: String?): JobStatusClass = when (status) {
  "running", "canceling" -> JobStatusClass.LIVE
  "created", "pending", "waiting_for_resource", "preparing", "manual", "scheduled" -> JobStatusClass.NOT_STARTED
  else -> JobStatusClass.TERMINAL
}

/** A job has a trace once it has started and its artifacts and trace were not erased. */
fun hasTrace(startedAt: String?, erasedAt: String?): Boolean = startedAt != null && erasedAt == null

/** Waiting-page status check interval: manual / scheduled 15 s, anything else 5 s. */
fun waitIntervalMs(status: String?): Long =
  if (status == "manual" || status == "scheduled") LONG_WAIT_MS else SHORT_WAIT_MS
