package com.gitlab.eclipse.mergerequests.diff

/**
 * UI-thread-owned generation state for MR file diff loads (REVIEW-01 design §5.1, F5), shaped like
 * [com.gitlab.eclipse.mergerequests.discussions.DiscussionGenerationRegistry].
 *
 * Generations are numbered per file identity ([MrFileIdentity], snapshot-independent), so a request
 * for a newer snapshot of a file supersedes an older one still in flight. An identical key (same
 * snapshot) already in flight is deduped instead: its content is fixed by SHA, so fetching it twice
 * is pointless. [counter], [latest] and [inFlight] are confined to the UI thread — that confinement
 * is the synchronization, so they must NOT be made volatile or synchronized. Only [active] is read
 * from background threads (the loader's cancellation check), hence `@Volatile`. [counter] is
 * monotonic and never reset by [onActivate], so a generation numbered before a stop can never be
 * mistaken for a fresh one.
 */
object MrFileDiffRegistry {
  /** False between the bundle's stop and its next start. */
  @Volatile
  var active: Boolean = true
    private set

  private var counter: Long = 0
  private val latest = HashMap<MrFileIdentity, Long>()
  private val inFlight = HashMap<MrFileDiffKey, Long>()

  /**
   * UI thread only. Numbers a load of [key] and makes it the latest for its file, or returns `null`
   * when the identical key is already the file's in-flight load, or while inactive.
   */
  fun begin(key: MrFileDiffKey): Long? {
    if (!active) return null
    val identity = key.fileIdentity()
    val running = inFlight[key]
    if (running != null && latest[identity] == running) return null
    counter += 1
    latest[identity] = counter
    inFlight[key] = counter
    return counter
  }

  /** UI thread only. Whether [generation] is still the latest load of [key]'s file, while active. */
  fun isCurrent(key: MrFileDiffKey, generation: Long): Boolean =
    active && latest[key.fileIdentity()] == generation

  /** UI thread only. Forgets [generation] of [key]; a stale or superseded generation changes nothing newer. */
  fun finish(key: MrFileDiffKey, generation: Long) {
    if (inFlight[key] == generation) inFlight.remove(key)
    val identity = key.fileIdentity()
    if (latest[identity] == generation) latest.remove(identity)
  }

  /** Stops every in-flight load from delivering; called on the UI thread by the bundle stop step. */
  fun onDeactivate() {
    active = false
  }

  /** Invalidates every generation numbered before now and re-enables loads; bundle start step. */
  fun onActivate() {
    latest.clear()
    inFlight.clear()
    active = true
  }

  /** Test-only: the registry is a process-wide singleton. */
  internal fun resetForTest() {
    counter = 0
    latest.clear()
    inFlight.clear()
    active = true
  }

  private fun MrFileDiffKey.fileIdentity() = MrFileIdentity(instanceUrl, projectId, mrIid, oldPath, newPath)
}
