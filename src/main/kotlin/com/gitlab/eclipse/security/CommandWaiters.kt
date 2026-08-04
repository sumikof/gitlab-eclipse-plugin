package com.gitlab.eclipse.security

import com.gitlab.eclipse.lsp.diagnostics.DiagnosticGenerationRegistry
import java.util.concurrent.atomic.AtomicLong

/** What a scan response found waiting for it. */
enum class WaiterMatch { COMMAND, NO_WAITER, EPOCH_MISMATCH }

/** A pending command scan: which request it was, and which connection it was sent on. */
private class Waiter(val id: Long, val epoch: Long)

/**
 * Remembers which scans a user command is still waiting for, keyed by normalised path (§14.6).
 *
 * Only command triggered scans are recorded. A scan started by a file save has nobody waiting for
 * it, so registering one would make the next response look like an answer to a command and produce
 * a report the user never asked for.
 *
 * The state is guarded by [DiagnosticGenerationRegistry.lock], deliberately the *same* monitor the
 * generation registry uses rather than one of its own. A response has to decide "is this still the
 * live connection" and "was anybody waiting for this" as one indivisible step; two monitors would
 * let the connection die in between. The lock order for the whole feature is fixed one way, outbound
 * `Mutex` first and this monitor second, so nothing here may ever acquire the outbound `Mutex`.
 *
 * Every operation checks the epoch, in both directions: a caller carrying a dead connection's epoch
 * is refused, and a waiter belonging to a dead connection is invisible to the live one. Without the
 * second half a late response from a restarted server would cancel a scan the user just started.
 *
 * Ids come from a single counter that only ever moves forwards. Neither [clear] nor a new epoch
 * resets it, because a reused id would let a cleared waiter's deadline fire against a different,
 * brand new scan.
 */
object CommandWaiters {
  private val lock = DiagnosticGenerationRegistry.lock
  private val ids = AtomicLong(0)
  private val waiters = mutableMapOf<String, MutableList<Waiter>>()
  private val armed = mutableSetOf<Long>()

  /** Registers a pending command scan for [path]. Returns its id, or `null` on a stale epoch. */
  fun add(path: String, connectionEpoch: Long): Long? {
    synchronized(lock) {
      if (connectionEpoch != DiagnosticGenerationRegistry.currentEpoch) return null
      val id = ids.incrementAndGet()
      waiters.getOrPut(path) { mutableListOf() }.add(Waiter(id, connectionEpoch))
      return id
    }
  }

  /**
   * Claims the oldest waiter on [path] for an incoming response. Responses are matched oldest first
   * because the server answers in order and carries nothing that identifies the request.
   */
  fun consumeOldest(path: String, connectionEpoch: Long): WaiterMatch {
    synchronized(lock) {
      if (connectionEpoch != DiagnosticGenerationRegistry.currentEpoch) return WaiterMatch.EPOCH_MISMATCH
      val queue = waiters[path] ?: return WaiterMatch.NO_WAITER
      val index = queue.indexOfFirst { it.epoch == connectionEpoch }
      if (index < 0) return WaiterMatch.NO_WAITER
      forget(path, queue.removeAt(index).id)
      return WaiterMatch.COMMAND
    }
  }

  /**
   * Cancels one specific waiter, used when its own request failed or timed out. It must never fall
   * back to "the oldest one": by the time a deadline fires its own scan may already have been
   * answered and a *different* scan may be waiting on the same path.
   */
  fun consumeById(waiterId: Long, connectionEpoch: Long): Boolean {
    synchronized(lock) {
      if (connectionEpoch != DiagnosticGenerationRegistry.currentEpoch) return false
      val entry = waiters.entries.firstOrNull { (_, queue) ->
        queue.any { it.id == waiterId && it.epoch == connectionEpoch }
      } ?: return false
      entry.value.removeAll { it.id == waiterId }
      forget(entry.key, waiterId)
      return true
    }
  }

  /**
   * Records that [waiterId] now has a response deadline running, so its send is accounted for.
   * A waiter that has already been consumed is not marked: nothing is left to time out.
   */
  fun markDeadlineArmed(waiterId: Long, connectionEpoch: Long) = synchronized(lock) {
    val live = connectionEpoch == DiagnosticGenerationRegistry.currentEpoch
    val known = waiters.values.any { queue -> queue.any { it.id == waiterId && it.epoch == connectionEpoch } }
    if (live && known) armed.add(waiterId)
    Unit
  }

  fun isDeadlineArmed(waiterId: Long): Boolean = synchronized(lock) { armed.contains(waiterId) }

  /**
   * Drops every waiter registered on [connectionEpoch] and reports how many per path.
   *
   * Filtering by epoch rather than wiping everything makes this safe to call either side of the
   * registry advancing its epoch, and keeps it from touching a connection it was not asked about.
   */
  fun clear(connectionEpoch: Long): Map<String, Int> = synchronized(lock) {
    val removed = mutableMapOf<String, Int>()
    waiters.entries.toList().forEach { (path, queue) ->
      val doomed = queue.filter { it.epoch == connectionEpoch }
      if (doomed.isEmpty()) return@forEach
      queue.removeAll(doomed.toSet())
      doomed.forEach { armed.remove(it.id) }
      removed[path] = doomed.size
      if (queue.isEmpty()) waiters.remove(path)
    }
    removed
  }

  /** Forgets a waiter that was just removed from [path]'s queue. */
  private fun forget(path: String, waiterId: Long) {
    armed.remove(waiterId)
    if (waiters[path]?.isEmpty() == true) waiters.remove(path)
  }

  fun resetForTest() = synchronized(lock) {
    waiters.clear()
    armed.clear()
    ids.set(0)
  }
}
