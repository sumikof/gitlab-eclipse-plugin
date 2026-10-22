package com.gitlab.eclipse.currentbranch

/**
 * Per-window decision whether an observed pipeline status is a *change* worth a notification (CI-03, design §4.7).
 * Fed every successful pipeline observation; the first observation of a `(gitDir, pipelineId)` is silent (startup,
 * window open, branch switch, a brand-new pipeline), a different status of the same pipeline notifies. Only the
 * last observed pipeline per repository is remembered, and the number of repositories is bounded.
 *
 * Failures, "no pipeline", "no repository" and loading states are simply not fed in: they neither notify nor reset
 * the memory, so a status change across a transient error is still reported. UI thread only.
 */
class PipelineChangeDetector(private val maxRepositories: Int = DEFAULT_MAX_REPOSITORIES) {
  private data class Seen(val pipelineId: Long, val status: String?)

  private val seen = object : LinkedHashMap<String, Seen>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Seen>): Boolean = size > maxRepositories
  }

  /** @return `true` when the caller should notify. */
  fun observe(gitDir: String, pipelineId: Long, status: String?): Boolean {
    val previous = seen.put(gitDir, Seen(pipelineId, status))
    return previous != null && previous.pipelineId == pipelineId && previous.status != status
  }

  /** Documented no-op counterpart of [observe] for non-observations; leaves the memory untouched. */
  fun ignore() = Unit

  companion object {
    const val DEFAULT_MAX_REPOSITORIES = 16
    private const val INITIAL_CAPACITY = 16
    private const val LOAD_FACTOR = 0.75f
  }
}
