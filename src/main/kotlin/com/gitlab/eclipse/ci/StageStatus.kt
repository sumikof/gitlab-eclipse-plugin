package com.gitlab.eclipse.ci

/**
 * Stage aggregate display name (design 3.4/4.4): the status of the entry with the highest
 * [CiStatus.priority]; ties go to the first entry. An empty list reads as unknown.
 */
fun aggregateStatusName(entries: List<StageEntry>): String {
  val (status, allowFailure) = aggregateStatus(entries.map { it.status to it.allowFailure })
  return CiStatus.displayName(status, allowFailure)
}

/** Same rule over bare (status, allowFailure) pairs; also used for the Downstream node. */
fun aggregateStatus(statuses: List<Pair<String?, Boolean>>): Pair<String?, Boolean> =
  statuses.maxByOrNull { (status, allowFailure) -> CiStatus.priority(status, allowFailure) } ?: (null to false)
