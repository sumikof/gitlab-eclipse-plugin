package com.gitlab.eclipse.currentbranch

/**
 * Process-wide guard so two windows on the same repository show one popup per status transition (CI-03, R18).
 * Remembers the last status shown per `(instanceUrl, projectId, pipelineId)` in a bounded LRU; a status that differs
 * from the remembered one is shown, so `failed -> success -> failed` after retries still notifies each time.
 * Keys are null-safe. Thread-safe.
 */
class PipelineNotificationDedupe(private val capacity: Int = DEFAULT_CAPACITY) {
  private data class Key(val instanceUrl: String, val projectId: Long?, val pipelineId: Long)

  private val lastShown = object : LinkedHashMap<Key, String?>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, String?>): Boolean = size > capacity
  }

  /** @return `true` when this transition was not shown yet (and records it). */
  @Synchronized
  fun shouldShow(instanceUrl: String, projectId: Long?, pipelineId: Long, status: String?): Boolean {
    val key = Key(instanceUrl, projectId, pipelineId)
    if (lastShown.containsKey(key) && lastShown[key] == status) return false
    lastShown[key] = status
    return true
  }

  companion object {
    const val DEFAULT_CAPACITY = 64
    private const val INITIAL_CAPACITY = 16
    private const val LOAD_FACTOR = 0.75f
  }
}
