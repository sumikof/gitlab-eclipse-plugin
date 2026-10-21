package com.gitlab.eclipse.handlers

/**
 * One value per owner (the pulldown menu of each trim tool bar, design §8.3 / I5). UI thread only.
 *
 * A value that is no longer alive is released and replaced on the next lookup; [remove] (called
 * when the owner is disposed) releases it. A failing release is contained.
 */
internal class OwnerScopedCache<K : Any, V : Any>(
  private val isAlive: (V) -> Boolean,
  private val release: (V) -> Unit,
) {
  private val entries = HashMap<K, V>()

  val size: Int get() = entries.size

  fun getOrCreate(owner: K, create: () -> V?): V? {
    entries[owner]?.let { existing ->
      if (isAlive(existing)) return existing
      remove(owner)
    }
    val created = create() ?: return null
    entries[owner] = created
    return created
  }

  fun remove(owner: K) {
    val value = entries.remove(owner) ?: return
    try {
      release(value)
    } catch (ignored: Exception) {
      /* already torn down with its owner: nothing left to release */
    }
  }
}
