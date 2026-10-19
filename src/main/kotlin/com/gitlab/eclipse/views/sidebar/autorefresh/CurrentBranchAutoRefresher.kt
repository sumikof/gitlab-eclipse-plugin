package com.gitlab.eclipse.views.sidebar.autorefresh

import kotlin.coroutines.cancellation.CancellationException

/** A scheduled action that can be withdrawn. */
fun interface Cancellable {
  fun cancel()
}

/** An installed listener that can be removed. */
fun interface Registration {
  fun unregister()
}

/** Monotonic time in milliseconds. */
fun interface AutoRefreshClock {
  fun nowMillis(): Long
}

/** Runs [schedule]d actions on the UI thread after a delay (production: `Display.timerExec`). */
fun interface AutoRefreshTimer {
  fun schedule(delayMillis: Long, action: () -> Unit): Cancellable
}

/** Whether the workbench has focus and the sidebar is visible; [register] reports changes on the UI thread. */
interface ActivitySource {
  fun isActive(): Boolean

  fun register(onChange: (Boolean) -> Unit): Registration
}

/** Delivers, on the UI thread, the git dir of a repository whose refs changed. */
fun interface RefsEventSource {
  fun register(onRefsChanged: (gitDir: String) -> Unit): Registration
}

/**
 * Reads a repository's HEAD off the UI thread and delivers the result on the UI thread. Contract:
 * `onResult` is called exactly once per [probe] call — with [HeadKey.Unavailable] when the read
 * fails or is cancelled — because the refresher probes one at a time and waits for that answer.
 */
fun interface HeadProbe {
  fun probe(gitDir: String, onResult: (HeadKey) -> Unit)
}

/**
 * Starts the view's current-branch-only ("soft") refresh. Contract: does not throw, and the view
 * reports the ticket's end through [CurrentBranchAutoRefresher.onRefreshSettled] in every case
 * (cancellation included); the refresher runs one refresh at a time and waits for that settle.
 */
fun interface SoftRefreshPort {
  fun startSoftRefresh(ticket: Long)
}

/** Why a refresh was requested; the only refresh detail the auto refresh logs. */
enum class RefreshReason { TIMER, FOCUS, BRANCH, MANUAL, COALESCED }

/**
 * Decides when the sidebar's "For current branch" section refreshes by itself (CI-04, design §3,
 * whose state table this follows): every [periodMillis] after the previous refresh *settled* while
 * [ActivitySource.isActive]; at once on regaining activity when that much time has passed; and
 * whenever a refs event on the tracked repository turns out to have moved HEAD (debounced by
 * [debounceMillis], checked through [HeadProbe]).
 *
 * At most one refresh runs at a time: requests arriving meanwhile collapse into one COALESCED
 * refresh after it settles. A manual refresh is adopted through [onManualRefreshStarted]; its ticket
 * replaces the soft one, so the superseded soft refresh's settle is ignored.
 *
 * UI-thread confined and SWT-free: every method and every port callback runs on the UI thread, so
 * there are no locks. After [stop] nothing it registered or scheduled has any effect, even if a
 * cancel was lost. Logs carry the [RefreshReason] or an exception class name only (design §5).
 *
 * TooManyFunctions is suppressed: each event row of the §3 table has its own handler, which keeps the
 * code checkable against the table row by row.
 */
@Suppress("TooManyFunctions")
class CurrentBranchAutoRefresher(
  private val clock: AutoRefreshClock,
  private val timer: AutoRefreshTimer,
  private val activity: ActivitySource,
  private val refs: RefsEventSource,
  private val probe: HeadProbe,
  private val port: SoftRefreshPort,
  private val log: (String) -> Unit,
  private val periodMillis: Long = DEFAULT_PERIOD_MILLIS,
  private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
) {
  private enum class Lifecycle { NEW, STARTED, STOPPED }

  private var lifecycle = Lifecycle.NEW

  /** Bumped by every [start] and [stop]; a callback captured under an older value is inert. */
  private var session = 0L
  private var registrations: List<Registration> = emptyList()
  private var lastTicket = 0L
  private var active = false
  private var inFlight: Long? = null
  private var pending = false
  private var periodic: Cancellable? = null
  private var lastSettledAt: Long? = null
  private var tracked: TrackedHead? = null
  private var debounce: Cancellable? = null
  private var probeInFlight = false
  private var probePending = false

  /** A new ticket, greater than every earlier one; the view takes manual-refresh tickets from here too. */
  fun nextTicket(): Long = ++lastTicket

  /**
   * Registers for activity and refs changes and, if active, arms the periodic timer. A no-op while
   * started. If a source refuses (e.g. a disposed display), whatever was registered is removed
   * again, the failure is logged by class name, and the refresher stays stopped: the view keeps
   * working with its manual Refresh, and a later [start] may try again.
   */
  fun start() {
    if (lifecycle == Lifecycle.STARTED) return
    val live = ++session
    val registered = mutableListOf<Registration>()
    try {
      registered += activity.register { if (live == session) onActiveChanged(it) }
      registered += refs.register { if (live == session) onRefsChanged(it) }
      active = activity.isActive()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      session++
      registered.forEach { contain { it.unregister() } }
      runCatching { log("Current-branch auto refresh could not start listening: ${e.javaClass.name}") }
      lifecycle = Lifecycle.STOPPED
      return
    }
    lifecycle = Lifecycle.STARTED
    registrations = registered
    if (active) armPeriodic(periodMillis)
  }

  /** Ends this refresher's effects for good (until a new [start]); idempotent, from any state. */
  fun stop() {
    if (lifecycle != Lifecycle.STARTED) {
      lifecycle = Lifecycle.STOPPED
      return
    }
    lifecycle = Lifecycle.STOPPED
    session++
    contain { periodic?.cancel() }
    contain { debounce?.cancel() }
    periodic = null
    debounce = null
    registrations.forEach { contain { it.unregister() } }
    registrations = emptyList()
    inFlight = null
    pending = false
    probeInFlight = false
    probePending = false
  }

  fun onManualRefreshStarted(ticket: Long) {
    if (lifecycle != Lifecycle.STARTED) return
    inFlight = ticket
    pending = false
    cancelPeriodic()
  }

  fun onRefreshSettled(ticket: Long, head: TrackedHead?) {
    if (lifecycle != Lifecycle.STARTED || ticket != inFlight) return
    inFlight = null
    lastSettledAt = clock.nowMillis()
    if (head != null) tracked = head
    if (pending) {
      pending = false
      request(RefreshReason.COALESCED)
    } else if (active) {
      armPeriodic(periodMillis)
    }
  }

  private fun onActiveChanged(nowActive: Boolean) {
    if (nowActive == active) return
    active = nowActive
    if (!nowActive) {
      cancelPeriodic()
      return
    }
    probeHead()
    val settledAt = lastSettledAt
    val sinceSettle = if (settledAt == null) Long.MAX_VALUE else clock.nowMillis() - settledAt
    if (sinceSettle >= periodMillis) {
      request(RefreshReason.FOCUS)
    } else if (inFlight == null) {
      armPeriodic(periodMillis - sinceSettle)
    }
  }

  private fun onRefsChanged(gitDir: String) {
    if (gitDir != tracked?.gitDir) return
    debounce?.cancel()
    debounce = schedule(debounceMillis) {
      debounce = null
      probeHead()
    }
  }

  private fun probeHead() {
    val gitDir = tracked?.gitDir ?: return
    if (probeInFlight) {
      probePending = true
      return
    }
    probeInFlight = true
    val live = session
    val issuedAt = lastTicket
    try {
      probe.probe(gitDir) { if (live == session) onProbed(gitDir, issuedAt, it) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // No answer will come: release the probe so later events can probe again.
      runCatching { log("Current-branch HEAD probe failed to start: ${e.javaClass.name}") }
      onProbed(gitDir, issuedAt, null)
    }
  }

  /**
   * [issuedAt]: the last ticket handed out when the probe was issued. A refresh started after that
   * reads HEAD at least as freshly as the probe did, so a change it reports is already covered (a
   * regain probes and then refreshes; without this the changed HEAD would add a COALESCED fetch).
   * [key] null: the probe never ran, so there is nothing to compare — only the bookkeeping.
   */
  private fun onProbed(gitDir: String, issuedAt: Long, key: HeadKey?) {
    probeInFlight = false
    val current = tracked
    val coveredByRunningRefresh = (inFlight ?: Long.MIN_VALUE) > issuedAt
    val moved = key != null && current != null && current.gitDir == gitDir && current.key != key
    if (moved && !coveredByRunningRefresh) request(RefreshReason.BRANCH)
    if (probePending) {
      probePending = false
      probeHead()
    }
  }

  private fun onPeriodicTick() {
    periodic = null
    if (active) request(RefreshReason.TIMER)
  }

  private fun request(reason: RefreshReason) {
    if (inFlight != null) {
      pending = true
      return
    }
    cancelPeriodic()
    val ticket = nextTicket()
    inFlight = ticket
    log("Current-branch auto refresh: $reason")
    try {
      port.startSoftRefresh(ticket)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Nothing will settle this ticket: release it so the next period (or HEAD change) can retry.
      inFlight = null
      runCatching { log("Current-branch auto refresh failed to start: ${e.javaClass.name}") }
      if (active) armPeriodic(periodMillis)
    }
  }

  private fun armPeriodic(delayMillis: Long) {
    cancelPeriodic()
    periodic = schedule(delayMillis) { onPeriodicTick() }
  }

  /** Schedules [action] for this session only: a timer whose cancel was lost does nothing after [stop]. */
  private fun schedule(delayMillis: Long, action: () -> Unit): Cancellable {
    val live = session
    return timer.schedule(delayMillis) { if (live == session) action() }
  }

  /** Runs one teardown step so that its failure cannot skip the others; logs the exception class only. */
  private fun contain(step: () -> Unit) {
    try {
      step()
    } catch (e: Exception) {
      runCatching { log("Current-branch auto refresh teardown failed: ${e.javaClass.name}") }
    }
  }

  private fun cancelPeriodic() {
    periodic?.cancel()
    periodic = null
  }

  companion object {
    const val DEFAULT_PERIOD_MILLIS = 30_000L
    const val DEFAULT_DEBOUNCE_MILLIS = 300L
  }
}
