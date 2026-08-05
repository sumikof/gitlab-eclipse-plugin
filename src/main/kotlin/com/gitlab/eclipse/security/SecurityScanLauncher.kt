package com.gitlab.eclipse.security

import com.gitlab.eclipse.authentication.GitLabTokenProviderManager
import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticGenerationRegistry
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticUri
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.Status
import org.eclipse.core.runtime.jobs.IJobChangeEvent
import org.eclipse.core.runtime.jobs.Job
import org.eclipse.core.runtime.jobs.JobChangeAdapter
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Why a scan request did or did not leave the plugin. */
enum class SecurityScanLaunchOutcome { SENT, DISABLED, NO_TOKEN, NO_EDITOR }

/**
 * Diagnostic source that every remote scan finding is filed under.
 *
 * A short fixed constant, never anything derived from the request: it is written onto every marker
 * and marker attributes are truncated, so a value that could grow would stop matching itself when
 * the markers have to be found again for removal.
 */
const val SECURITY_SCAN_SOURCE = "gitlab_security_scan"

/** How long a user command waits for its answer before it is told there will not be one. */
private const val RESPONSE_DEADLINE_MS = 60_000L

/** Name of the platform job that runs an answer deadline. `internal` so tests can find it. */
internal const val DEADLINE_JOB = "GitLab security scan response deadline"

/**
 * Runs [onDue] once the delay has passed.
 *
 * An implementation **must run [onDue] even if the delay can never elapse, and must not throw**.
 * The caller marks the deadline armed before calling this, which stands the send's own failure
 * reporting down, so from that point nothing else is left to close the request out. [onDue] is
 * written to tolerate being called more than once, so an implementation may simply call it again
 * rather than track whether it already did.
 */
internal typealias DeadlineScheduler = (delayMs: Long, onDue: () -> Unit) -> Unit

/**
 * Runs an answer deadline on a platform [Job], deliberately not on the shared `CoroutineScope`.
 *
 * That scope is built on a plain `Job`, not a `SupervisorJob` (`WorkspaceModule`), so a single
 * uncaught failure in any child cancels it, and every later `launch` anywhere in the plugin —
 * configuration sends, chat, code suggestions — then silently does nothing for the rest of the
 * session. This body ends in a user notification, which resolves a `Display` and can throw once the
 * workbench is gone, so it is exactly the kind of body that must not run there. The platform catches
 * and logs what a job throws instead.
 *
 * [onDue] runs from three places, because each covers a way the previous one can fail to happen: the
 * job body is the normal path, the completion listener covers a job the platform cancelled before it
 * ever ran, and the scheduling guard covers a job the platform refused to take at all. `JobManager`
 * rejects every `schedule` with `IllegalStateException("Job manager has been shut down.")` once the
 * workbench is stopping, and by then the deadline is already marked armed, so without that last one
 * the waiter would never be released. Running more than once is harmless: the second call finds
 * nothing left to release.
 */
internal fun schedulePlatformDeadline(delayMs: Long, onDue: () -> Unit) {
  val logger = logger<SecurityScanLauncher>()
  val entered = AtomicBoolean(false)

  // Nothing this runs may escape: inside the job it would become a failure status and raise a
  // platform error dialog over what is only a background timer, and inside the listener or the
  // scheduling guard it would escape to the caller. Only the class name is recorded.
  fun release() = runCatching(onDue)
    .onFailure { logger.warn("Failed to expire a security scan request: ${it::class.simpleName}") }
    .let { }

  val job = object : Job(DEADLINE_JOB) {
    override fun run(monitor: IProgressMonitor?): IStatus {
      entered.set(true)
      release()
      // Always OK_STATUS, for the same reason the diagnostics jobs report success and log instead.
      return Status.OK_STATUS
    }
  }
  job.isSystem = true
  job.addJobChangeListener(object : JobChangeAdapter() {
    override fun done(event: IJobChangeEvent?) {
      if (!entered.get()) logger.warn("A remote security scan deadline never started.")
      release()
    }
  })
  runCatching { job.schedule(delayMs) }.onFailure {
    logger.warn("Failed to schedule a security scan deadline: ${it::class.simpleName}")
    release()
  }
}

/**
 * What a command is told when the feature it invokes is switched off.
 *
 * Says where the switch is, because "nothing happened" and "you have not opted in" look identical
 * from the menu, and the setting defaults to off — so this is what the *first* invocation hits.
 */
private const val DISABLED_MESSAGE =
  "GitLab security scan is turned off. Enable real-time SAST scan in the GitLab preferences to " +
    "run it."
private const val NO_EDITOR_MESSAGE =
  "Open a file in the editor to run a GitLab security scan on it."
private const val NO_TOKEN_MESSAGE =
  "Sign in to GitLab to run a security scan."

/** Design §11.3 row 4. Fixed client-side text, like every other outcome the user is shown. */
private const val TIMED_OUT_MESSAGE =
  "GitLab security scan: no response from the language server."

/**
 * Decides whether a scan request may leave the plugin, and builds it.
 *
 * This is deliberately a pure function taking [send] and [notify] as parameters. Remote scanning
 * uploads the contents of the user's file to their GitLab instance, so the one property that has to
 * be provable rather than reviewable is "when the feature is off, [send] is never invoked". Passing
 * the effects in makes that a direct assertion instead of an indirect one about a mock.
 *
 * The gates are evaluated in the fixed order `enabled` -> `uri` -> `hasToken` (design §10.2). The
 * order is not an implementation detail: a user who never turned the feature on must not be told
 * about the editor or about signing in, so [enabled] has to be answered before anything that can
 * produce a message. It answers with a message of its own now, but only the one that names the
 * setting — never one that implies the scan was attempted.
 *
 * A trigger the user did not ask for stays silent. Only [SecurityScanSource.COMMAND] notifies,
 * because only there is somebody waiting for an answer; a save that cannot be scanned must not
 * interrupt typing. That holds for the disabled gate as much as the others: a user who has not
 * opted in must not be told about this feature on every single save.
 */
internal fun runSecurityScan(
  uri: String?,
  source: SecurityScanSource,
  enabled: Boolean,
  hasToken: Boolean,
  send: (SecurityScanParams) -> Unit,
  notify: (String) -> Unit,
): SecurityScanLaunchOutcome {
  // A command gets an answer even here (F6): the setting defaults to off, so the very first press
  // of the menu item lands on this gate, and showing nothing makes the item look broken. A save
  // stays silent — the original intent, that a user who never opted in hears nothing on every
  // single save, is the half that must not move.
  //
  // Still no audit line either way: the feature is off, nothing left the machine, and recording
  // activity for users who have not opted in is exactly what opt-in is meant to prevent.
  if (!enabled) {
    if (source == SecurityScanSource.COMMAND) notify(DISABLED_MESSAGE)
    return SecurityScanLaunchOutcome.DISABLED
  }
  if (uri.isNullOrBlank()) {
    if (source == SecurityScanSource.COMMAND) notify(NO_EDITOR_MESSAGE)
    return SecurityScanLaunchOutcome.NO_EDITOR
  }
  if (!hasToken) {
    if (source == SecurityScanSource.COMMAND) notify(NO_TOKEN_MESSAGE)
    return SecurityScanLaunchOutcome.NO_TOKEN
  }
  send(SecurityScanParams(uri, source.wireValue))
  return SecurityScanLaunchOutcome.SENT
}

/**
 * Sends remote security scan requests and keeps the books that let a response find its request.
 *
 * The feature is opt-in and off by default because it uploads the file being scanned to the user's
 * GitLab instance. Everything here is arranged around that: the gate is asked first and asked again
 * under the outbound lock, so a user who switches the feature off while a request is queued does not
 * get one last upload.
 *
 * Nothing is logged that could carry content off the machine's own log: no file paths, no token, and
 * never the language server's own error text.
 */
class SecurityScanLauncher(
  private val preferenceStore: ScopedPreferenceStore,
  private val languageServerWrapper: GitLabLanguageServerWrapper,
  private val configurationService: GitLabLanguageServerConfigurationService,
  private val tokenProviderManager: GitLabTokenProviderManager,
  private val coroutineScope: CoroutineScope,
  private val outboundLock: Mutex,
  private val scheduleDeadline: DeadlineScheduler = ::schedulePlatformDeadline,
  private val notify: (String) -> Unit = NotificationUtils::show,
) {
  private val logger by lazy { logger<SecurityScanLauncher>() }

  /**
   * Entry point for both triggers. Returns what happened so a caller can react without having to
   * reproduce the gate logic.
   *
   * The connection epoch and the server proxy are both captured here, at call time. A restart
   * between this call and the coroutine actually running must strand the request with the connection
   * it was meant for, never redirect it at the new one.
   */
  fun launch(uri: String?, source: SecurityScanSource): SecurityScanLaunchOutcome {
    val epoch = DiagnosticGenerationRegistry.currentEpoch
    val enabled = isEnabled()
    // Converge the source's suspended parity with the setting before any gate is evaluated: a
    // settings transition that was overtaken in the queue may have left the parity behind, and a
    // scan must not ride on a stale one (design §9.1.1).
    DiagnosticGenerationRegistry.reconcileSource(SECURITY_SCAN_SOURCE, desiredSuspended = !enabled)

    val path = DiagnosticUri.normalize(uri)
    // A URI that does not resolve to a file on disk cannot be scanned, and a response could not
    // be matched back to it either, so it counts as "no editor".
    val scanUri = if (path == null) null else uri
    val server = languageServerWrapper.languageServer
    val outcome = runSecurityScan(
      uri = scanUri,
      source = source,
      enabled = enabled,
      // Short-circuited on purpose, and short-circuited on **both** of the gates that precede this
      // one. Kotlin evaluates arguments at the call site, so anything not guarded here is read on
      // the way in, before the gate that is supposed to stop everything. Reading the token goes to
      // Equinox secure storage, which can block and can raise the master password prompt: with the
      // save trigger wired up, a user who never opted in would get a credential prompt on every
      // save, and a user who did opt in would get one for every command with no active file or an
      // untitled editor. The condition mirrors the two gates exactly, so the outcome is unchanged:
      // DISABLED and NO_EDITOR both already win over NO_TOKEN in the gate order.
      hasToken = enabled && !scanUri.isNullOrBlank() && tokenProviderManager.getToken().isNotBlank(),
      // `path` is non-null on every path that reaches this lambda: a null one made `uri` null
      // above, and the gates answer NO_EDITOR for that before send is ever called.
      send = { params -> if (path != null) dispatch(params, path, source, server, epoch) },
      notify = notify,
    )
    // Audited only once the request is really on its way, and without the path: the point of the
    // record is that something left the machine, and a disabled feature has nothing to record.
    if (outcome == SecurityScanLaunchOutcome.SENT) {
      logger.info("Requested a remote GitLab security scan (source=${source.wireValue}).")
    }
    return outcome
  }

  /**
   * Queues the request. The waiter is registered here rather than inside the coroutine so it exists
   * before anything can be answered, and only for a command: a save has nobody waiting for it.
   */
  private fun dispatch(
    params: SecurityScanParams,
    path: String,
    source: SecurityScanSource,
    server: GitLabLanguageServer?,
    epoch: Long,
  ) {
    val waiterId = if (source == SecurityScanSource.COMMAND) CommandWaiters.add(path, epoch) else null
    // `add` refuses on a stale epoch: the server restarted between [launch] reading the epoch and
    // this line. Sending anyway would label the request a command with nothing waiting for it, so
    // the answer would later be settled as a save and the user — who pressed a button — would be
    // told nothing at all (design F6). Report it as what it is, in the same words a restart that
    // caught the request one step later already uses. A save is unaffected: it registers no waiter
    // by design, and its `null` means something else entirely.
    //
    // Both effects run here, outside every lock: `add` has already released the registry monitor,
    // and neither an audit nor a notification may be reached while it is held. The audit goes
    // through the one shared builder, and the message is the one a restart-cancelled scan already
    // uses — this *is* a restart, caught a moment earlier than the usual path catches it.
    if (source == SecurityScanSource.COMMAND && waiterId == null) {
      audit(
        securityScanAuditLine(
          source = SecurityScanSource.COMMAND,
          outcome = OUTCOME_CANCELLED,
          status = null,
          findings = null,
          exceptionType = null,
          path = path,
        )
      )
      notify(CANCELLED_MESSAGE)
      return
    }
    val entered = AtomicBoolean(false)
    // What the send threw, if it threw, so the completion handler can name it in the audit line.
    // Only ever the class name: an lsp4j failure quotes the request it was carrying.
    val failureType = AtomicReference<String?>(null)
    val job = coroutineScope.launch {
      entered.set(true)
      val failure = contained {
        // ONE coroutine, ONE lock region, both notifications in order. Splitting them across two
        // coroutines would only give mutual exclusion: a Mutex does not hand the lock out in the
        // order it was asked for, so the scan could overtake the configuration that enables it.
        outboundLock.withLock {
          if (!stillEnabled()) {
            // Switched off between the gate and the send. Drop the request and say nothing: the
            // user turning the feature off is the answer.
            if (waiterId != null) CommandWaiters.consumeById(waiterId, epoch)
            logger.info("Remote security scan was turned off before the request was sent.")
            return@withLock
          }
          // No server means nothing was sent; leaving the deadline unarmed lets the completion
          // handler below report it as a failure.
          val target = server ?: return@withLock
          // `buildParams()` reads SECURITY_SCAN_ENABLED a second time, so a flip between the check
          // above and this line sends `remoteSecurityScans=false` and then the scan request. That
          // fails safe: the server has just been told the feature is off, and the only thing that
          // left the plugin is a URI — never the file's contents. Closing the window would mean
          // holding the registry monitor across both sends, which inverts the lock order.
          target.didChangeConfiguration(DidChangeConfigurationParams(configurationService.buildParams()))
          target.runSecurityScan(params)
          if (waiterId != null) armDeadline(waiterId, path, epoch)
        }
      }
      failureType.set(failure)
    }
    // A cancelled scope makes `launch` return a completed job without throwing and without ever
    // running the body, so try/catch cannot see it. This can. It also runs after a contained
    // failure, which is how a send that threw still reaches the user as a failure.
    job.invokeOnCompletion {
      reportIfNeverSent(waiterId, epoch, path, source, entered.get(), failureType.get())
    }
  }

  /**
   * Runs the sending body so that no failure reaches the scope's parent job.
   *
   * The shared scope is built on a plain `Job`, not a `SupervisorJob` (`WorkspaceModule`): one
   * escaping failure cancels it, and from then on every `launch` anywhere in the plugin —
   * configuration sends, chat, code suggestions — silently does nothing for the rest of the session.
   * An lsp4j proxy whose stream has died throws straight out of the two sends below, so this is a
   * reachable path, not a defensive flourish.
   *
   * Cancellation keeps its normal meaning and is rethrown: it is not a failure and does not cancel
   * the parent. Only the exception's class name is recorded — an lsp4j failure can quote the request
   * it was carrying, and no path may ever reach the log. That class name is also what is returned,
   * so the completion handler can put it in the audit line; `null` means the body finished.
   */
  private inline fun contained(body: () -> Unit): String? {
    try {
      body()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      return e::class.simpleName ?: e.javaClass.name
    }
    return null
  }

  /**
   * Re-reads the gate under the registry monitor, which is the lock the settings transitions use, so
   * this cannot observe a half applied change.
   *
   * Called while the outbound `Mutex` is held. That is the one permitted nesting order for this
   * feature; taking the `Mutex` while holding this monitor would close the cycle.
   */
  private fun stillEnabled(): Boolean = synchronized(DiagnosticGenerationRegistry.lock) {
    isEnabled() && !DiagnosticGenerationRegistry.isSuspended(SECURITY_SCAN_SOURCE)
  }

  private fun isEnabled(): Boolean =
    preferenceStore.getBoolean(PreferenceConstants.SECURITY_SCAN_ENABLED)

  /**
   * Starts the answer deadline for a request that really went out.
   *
   * The timer is a sibling of the send, never a child of it: a child would keep the send's job alive
   * for the whole minute and postpone the "did this ever send" check by exactly that long. It runs on
   * a platform job rather than the shared scope; see [schedulePlatformDeadline] for why.
   *
   * Marked armed **before** it is scheduled, so the two can never both stand down. Arming makes
   * [reportIfNeverSent] stand down, which leaves the deadline as the only thing that can still close
   * the request out — hence the scheduler's obligation to run [expire] even when the delay cannot
   * elapse. Scheduling with a delay returns immediately, so doing it under the outbound lock costs
   * nothing.
   */
  private fun armDeadline(waiterId: Long, path: String, epoch: Long) {
    CommandWaiters.markDeadlineArmed(waiterId, epoch)
    scheduleDeadline(RESPONSE_DEADLINE_MS) { expire(waiterId, path, epoch) }
  }

  /**
   * Releases a request whose answer will not come. Safe to call repeatedly and from any thread: the
   * waiter can only be removed once, so the message is shown exactly once however often this runs.
   *
   * By id, never "the oldest": this request may already have been answered and a *different* scan
   * may be queued on the same file by the time the deadline comes due.
   */
  private fun expire(waiterId: Long, path: String, epoch: Long) {
    if (!CommandWaiters.consumeById(waiterId, epoch)) return
    audit(
      securityScanAuditLine(
        source = SecurityScanSource.COMMAND,
        outcome = OUTCOME_TIMEOUT,
        status = null,
        findings = null,
        exceptionType = null,
        path = path,
      )
    )
    notify(TIMED_OUT_MESSAGE)
  }

  /**
   * Writes an audit line, and survives failing to.
   *
   * A log call is not free of failure — the platform log can be gone while the workbench is
   * stopping — and this runs on the paths that are the *only* thing left to release a waiting
   * command. Losing the record must not also lose the notification (Phase 5A).
   */
  private fun audit(line: String) {
    runCatching { logger.info(line) }
  }

  /**
   * Runs when the sending coroutine finished, however it finished.
   *
   * An armed deadline means the request went out and is somebody else's problem now. Otherwise the
   * waiter is cancelled, and only a waiter that was still standing counts as a failure worth showing
   * — a request that was deliberately abandoned, or answered already, has removed its own.
   */
  private fun reportIfNeverSent(
    waiterId: Long?,
    epoch: Long,
    path: String,
    source: SecurityScanSource,
    entered: Boolean,
    failureType: String?,
  ) {
    if (waiterId != null && CommandWaiters.isDeadlineArmed(waiterId)) return
    // Not an outcome, so not an audit line: this says the plugin's own scope is broken, which is
    // worth knowing whatever the scan did.
    if (!entered) logger.warn("A remote security scan request never started.")

    // A save registers no waiter, so there is nothing to claim and nothing to tell the user
    // (§11.3 row 9); the send's own evidence is the only thing that says it failed.
    if (waiterId == null) {
      if (failureType != null || !entered) auditNotSent(source, path, failureType)
      return
    }
    // A waiter that has already gone was answered, or deliberately abandoned. Neither is a failure.
    if (!CommandWaiters.consumeById(waiterId, epoch)) return
    auditNotSent(source, path, failureType)
    // The status is genuinely unknown here — nothing was sent, so nothing answered — which is
    // exactly the case the generic message is worded for (§11.4). Kept as one definition rather
    // than a second constant saying the same thing in different words.
    notify(SecurityScanStatusReporter.messageForStatus(null))
  }

  private fun auditNotSent(source: SecurityScanSource, path: String, failureType: String?) = audit(
    securityScanAuditLine(
      source = source,
      outcome = OUTCOME_FAILURE,
      status = null,
      findings = null,
      exceptionType = failureType,
      path = path,
    )
  )
}
