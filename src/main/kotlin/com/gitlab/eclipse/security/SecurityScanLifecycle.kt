package com.gitlab.eclipse.security

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticGenerationRegistry
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticMarkerService
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val lifecycleLog by lazy { logger<SecurityScanLifecycle>() }

/**
 * Runs one step of a teardown so that the rest of the teardown still happens.
 *
 * Every caller below is on a path that must not throw: the bundle's `stop()`, the language server's
 * lifecycle lock, and a coroutine on the plugin's shared scope, which is built on a plain `Job` and
 * would be cancelled for the rest of the session by one escaping failure. Cancellation keeps its
 * normal meaning and is rethrown.
 *
 * Only the exception's class name is recorded. These paths quote the scanned file, and no absolute
 * path may reach the error log (design §16.1); writing the log line is itself contained, because a
 * failing log must not take the rest of the teardown down with it.
 */
private inline fun contain(what: String, body: () -> Unit) {
  try {
    body()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Throwable) {
    runCatching { lifecycleLog.warn("Security scan lifecycle failed while $what: ${e::class.simpleName}") }
  }
}

/**
 * The single implementation of "this language server connection ended" and "the bundle is stopping".
 *
 * It exists as one object rather than as code at each call site because the order of the steps is
 * the whole content of it, and two of the calls want *opposite* values of the same counter:
 *
 *  - [CommandWaiters.clear], reached through [SecurityScanStatusReporter.cancelPending], wants the
 *    epoch of the connection that died — read **before** the registry is advanced. Given the new
 *    one it removes nothing, so every pending command leaks and the cancellation notice it is
 *    waiting for is never produced (design F6).
 *  - [DiagnosticMarkerService.deleteMarkersNotInEpoch] wants the epoch the registry advanced **to**,
 *    because it keeps what matches. Given the dead one the predicate inverts and it deletes the
 *    live connection's markers instead of the dead one's.
 *
 * Both server-stop routes — `GitLabLanguageServerProcessProvider.stopLocked` and the process's own
 * `onExit` — call [onServerStopped], so "the two routes do the same thing in the same order" is true
 * by construction rather than by review. `onExit` alone would miss every explicit stop and restart;
 * `stopLocked` alone would miss every crash and self-inflicted exit.
 *
 * Nothing here holds [DiagnosticGenerationRegistry.lock] across a notification, a scheduled job or
 * an outbound send: each registry call takes the monitor and gives it straight back, and the marker
 * clean up is scheduled with no monitor held. The one nesting this feature permits is outbound
 * `Mutex` first and the registry monitor second, which is the direction [SecurityScanSettings] uses.
 *
 * **No function here throws.** They run from the bundle's `stop()`, from under the language server's
 * lifecycle lock and from a shared coroutine scope, and all three treat an escaping failure as
 * something much worse than the step that failed.
 */
object SecurityScanLifecycle {
  /**
   * The language server connection ended, however it ended.
   *
   * Idempotent for one connection: [DiagnosticGenerationRegistry.onServerStopped] advances the epoch
   * only once, so a stop that is followed by the process's own exit notification cannot advance it
   * twice and strand the connection that started in between.
   */
  fun onServerStopped() = onServerStopped(markerService(), NotificationUtils::show, ::audit)

  /**
   * As [onServerStopped], with the effects passed in.
   *
   * The seam exists because the two epochs above are the property that has to be provable: a test
   * can only tell "before" from "after" by watching what each of these two receives.
   */
  internal fun onServerStopped(
    markerService: DiagnosticMarkerService?,
    notify: (String) -> Unit,
    audit: (String) -> Unit,
  ) {
    // (1) BEFORE the advance: this is the connection whose waiters have to go.
    val dead = DiagnosticGenerationRegistry.currentEpoch
    // (2) Advances the epoch, once per connection.
    DiagnosticGenerationRegistry.onServerStopped()
    // (3) The dead epoch, deliberately not the current one.
    val report = SecurityScanStatusReporter.cancelPending(dead, ScanCancelReason.SERVER_STOPPED)
    // (4) Audit first, then the user: a log that cannot be written must not swallow the one
    // notification a waiting command is going to get.
    report.auditLines.forEach { line -> contain("auditing a cancelled scan") { audit(line) } }
    report.notify?.let { message -> contain("reporting a cancelled scan") { notify(message) } }
    // (5) AFTER the advance: markers of the new epoch are kept, everything older goes. Reading the
    // registry again rather than reusing `dead` is the whole point of the step.
    val live = DiagnosticGenerationRegistry.currentEpoch
    // Scheduling this resolves the workspace root on this thread, which throws once the workspace
    // is closed — reachable on the bundle-stop path, hence contained rather than trusted.
    contain("cleaning up the markers of a dead connection") { markerService?.deleteMarkersNotInEpoch(live) }
  }

  /**
   * The bundle is stopping.
   *
   * Runs **after** the language server stop path, so the waiters are dropped and their deadlines are
   * released while the workbench is still there to show what happened; deactivating first would
   * leave in-flight deadline jobs notifying a workbench that is on its way out.
   *
   * [SecurityScanSaveListener.uninstall] is not optional: the listener is held by every
   * `IDocumentProvider` it attached to, and those outlive this bundle.
   */
  fun onBundleStopping() = onBundleStopping(markerService(), saveListener())

  /** As [onBundleStopping], with the collaborators passed in. */
  internal fun onBundleStopping(
    markerService: DiagnosticMarkerService?,
    saveListener: SecurityScanSaveListener?,
  ) {
    // Each step is contained on its own: a workspace that is already closed must not be the reason
    // the save listener stays attached to every document provider in the workbench.
    contain("deactivating the diagnostics registry") { DiagnosticGenerationRegistry.onDeactivate() }
    contain("deleting the diagnostics markers") { markerService?.deleteAllMarkers() }
    contain("uninstalling the security scan save trigger") { saveListener?.uninstall() }
  }

  /**
   * Koin is gone by the time a late stop runs, and a lookup that fails is not a reason to skip the
   * rest of the teardown; the step that needed it is simply skipped.
   */
  private fun markerService(): DiagnosticMarkerService? =
    runCatching { service<DiagnosticMarkerService>() }.getOrNull()

  private fun saveListener(): SecurityScanSaveListener? =
    runCatching { service<SecurityScanSaveListener>() }.getOrNull()

  private fun audit(line: String) {
    lifecycleLog.info(line)
  }
}

/**
 * Applies a change to the remote scanning setting, off the UI thread.
 *
 * The sequence number is taken on the UI thread, in the turn the user pressed OK, and travels with
 * the work; the queue that runs it gives no order guarantee of its own, so without it the second of
 * two rapid transitions could be applied first and then undone by the first (design §9.1.1).
 *
 * Switching the feature off is destructive — it drops the commands that are waiting and removes the
 * markers this source published — so those steps run **only** when this transition is still the
 * latest one, and the check and the steps are closed inside one region of the registry monitor. A
 * check made outside it would be answered a moment before a newer transition arrived.
 *
 * Neither direction notifies. Switching the feature off is itself the answer, and saying it again
 * would be noise; only the audit records what happened (design §11.3).
 *
 * The connection epoch is never advanced here: `connectionEpoch` and `settingsSeq` are different
 * axes, and moving the first one would tell every live request that its connection died.
 */
class SecurityScanSettings(
  private val coroutineScope: CoroutineScope,
  private val outboundLock: Mutex,
  private val markerService: DiagnosticMarkerService,
) {
  /** What the locked region decided, so the rest can run without reading state again. */
  private data class Disabled(val report: CancellationReport, val watermark: Long)

  /**
   * Call from the UI thread when the setting really changed value.
   *
   * The sequence number is taken here, synchronously, and the rest is handed to the plugin's scope:
   * the outbound lock has to be taken before anything is applied, and the UI thread may not block on
   * it.
   */
  fun onSettingChanged(enabled: Boolean) {
    val seq = DiagnosticGenerationRegistry.nextSettingsSeq()
    contain("scheduling a remote security scan setting change") {
      coroutineScope.launch {
        contain("applying a remote security scan setting change") { applyTransition(enabled, seq) }
      }
    }
  }

  /**
   * The body of a transition. `internal` so a test can run it without a scheduler in the way.
   *
   * Held under the outbound `Mutex` for the same reason a scan is: a request that is already queued
   * must not overtake the transition that decides whether it may be sent at all.
   */
  internal suspend fun applyTransition(enabled: Boolean, seq: Long) {
    outboundLock.withLock {
      if (enabled) {
        // Nothing destructive to do, so nothing to gate: a transition that was overtaken simply
        // does not apply, and `resumeSource` says so.
        if (DiagnosticGenerationRegistry.resumeSource(SECURITY_SCAN_SOURCE, seq)) {
          // Whatever was learned about this file's failures was learned before the user switched the
          // feature off; the next failure is news again.
          SecurityScanStatusReporter.onScanningReenabled()
        }
        return@withLock
      }
      // `false` = a newer transition already applied. Doing anything now would undo it.
      if (!DiagnosticGenerationRegistry.suspendSource(SECURITY_SCAN_SOURCE, seq)) return@withLock
      val disabled = synchronized(DiagnosticGenerationRegistry.lock) {
        // The latest-check and the destructive steps share one region on purpose. Split across two,
        // a transition that was overtaken between them would still drop the new one's waiters.
        if (!DiagnosticGenerationRegistry.isLatestSettingsSeq(seq)) {
          null
        } else {
          Disabled(
            // The *current* epoch: the connection is alive, only the setting changed.
            SecurityScanStatusReporter.cancelPending(
              DiagnosticGenerationRegistry.currentEpoch,
              ScanCancelReason.SETTING_DISABLED,
            ),
            // Captured inside the region, so anything published after this decision is newer than
            // it and survives the clean up below.
            DiagnosticGenerationRegistry.currentGenerationCounter(),
          )
        }
      } ?: return@withLock
      // Outside the monitor: the audit resolves paths and the clean up schedules a workspace job.
      disabled.report.auditLines.forEach { line -> contain("auditing a cancelled scan") { audit(line) } }
      contain("cleaning up the markers of a disabled source") {
        markerService.deleteMarkersBySource(SECURITY_SCAN_SOURCE, disabled.watermark)
      }
    }
  }

  private fun audit(line: String) {
    lifecycleLog.info(line)
  }
}
