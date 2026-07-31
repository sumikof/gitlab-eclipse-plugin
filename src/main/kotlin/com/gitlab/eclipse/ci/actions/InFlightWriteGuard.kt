package com.gitlab.eclipse.ci.actions

import java.util.concurrent.ConcurrentHashMap

/**
 * Identity of one CI write target (design §8.5, FR-6): the normalized instance url plus the
 * kind ("pipeline" | "job") and numeric id of the object being written to. The ACTION is
 * deliberately NOT part of the key so a retry and a cancel on the same target serialize
 * instead of racing each other.
 */
data class WriteKey(val instanceUrl: String, val targetKind: String, val targetId: Long)

/**
 * Process-wide in-flight guard serializing CI write actions per [WriteKey] (FR-6). A handler
 * acquires the key on the UI thread BEFORE launching the background write and releases it in
 * the coroutine's `finally` so success, failure, and cancellation all free the target.
 */
object InFlightWriteGuard {
  // Any: WriteKey (retry/cancel/play) and CreateWriteKey are distinct data classes and never
  // compare equal, so one guard safely serializes both without cross-collision.
  private val inFlight = ConcurrentHashMap.newKeySet<Any>()

  /** True if the caller now owns the key; false if a write to the same target is in flight. */
  fun tryAcquire(key: Any): Boolean = inFlight.add(key)

  fun release(key: Any) {
    inFlight.remove(key)
  }
}
