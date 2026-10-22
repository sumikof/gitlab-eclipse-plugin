package com.gitlab.eclipse.ci

import com.gitlab.eclipse.api.model.GitLabBridge
import com.gitlab.eclipse.api.model.GitLabJob

const val NO_STAGE = "(no stage)"

sealed interface StageEntry {
  val id: Long
  val sortId: Long
  val stage: String?
  val status: String?
  val allowFailure: Boolean
}

data class JobEntry(val job: GitLabJob, val firstAttemptId: Long?) : StageEntry {
  override val id: Long get() = job.id
  override val sortId: Long get() = firstAttemptId ?: job.id
  override val stage: String? get() = job.stage
  override val status: String? get() = job.status
  override val allowFailure: Boolean get() = job.allowFailure ?: false
}

data class BridgeEntry(val bridge: GitLabBridge) : StageEntry {
  override val id: Long get() = bridge.id
  override val sortId: Long get() = bridge.id
  override val stage: String? get() = bridge.stage
  override val status: String? get() = bridge.status
  override val allowFailure: Boolean get() = bridge.allowFailure ?: false
}

/**
 * Port of VS Code's `keepLatestAttempts`: jobs fetched with `include_retried=true` are grouped by name, the
 * highest id (latest attempt) is kept, and the lowest id is remembered as [JobEntry.firstAttemptId] when it
 * differs, so a retried job keeps its original place in the tree. Jobs without a name are never grouped.
 */
fun keepLatestAttempts(jobs: List<GitLabJob>): List<JobEntry> {
  val unnamed = jobs.filter { it.name == null }.map { JobEntry(it, null) }
  val named = jobs.filter { it.name != null }.groupBy { it.name }.values.map { attempts ->
    val latest = attempts.maxBy { it.id }
    val first = attempts.minOf { it.id }
    JobEntry(latest, first.takeIf { it != latest.id })
  }
  return named + unnamed
}

/**
 * Merges job and bridge entries, sorts them by [StageEntry.sortId] and groups them by stage (`null` stage ->
 * [NO_STAGE]) in first-appearance order, so stage order and in-stage order are both stable across retries.
 */
fun orderStageEntries(jobs: List<JobEntry>, bridges: List<GitLabBridge>): List<Pair<String, List<StageEntry>>> {
  val all: List<StageEntry> = (jobs + bridges.map { BridgeEntry(it) }).sortedBy { it.sortId }
  return all.groupBy { it.stage ?: NO_STAGE }.map { (stage, entries) -> stage to entries }
}
