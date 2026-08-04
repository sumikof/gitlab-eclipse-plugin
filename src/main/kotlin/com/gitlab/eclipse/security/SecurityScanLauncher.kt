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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.util.concurrent.atomic.AtomicBoolean

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

private const val NO_EDITOR_MESSAGE =
  "Open a file in the editor to run a GitLab security scan on it."
private const val NO_TOKEN_MESSAGE =
  "Sign in to GitLab to run a security scan."
private const val SEND_FAILED_MESSAGE =
  "GitLab could not start the security scan."
private const val TIMED_OUT_MESSAGE =
  "The GitLab security scan did not answer in time."

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
 * produce a message.
 *
 * A trigger the user did not ask for stays silent. Only [SecurityScanSource.COMMAND] notifies,
 * because only there is somebody waiting for an answer; a save that cannot be scanned must not
 * interrupt typing.
 */
internal fun runSecurityScan(
  uri: String?,
  source: SecurityScanSource,
  enabled: Boolean,
  hasToken: Boolean,
  send: (SecurityScanParams) -> Unit,
  notify: (String) -> Unit,
): SecurityScanLaunchOutcome {
  // No notification and no audit trail here: the feature is off, so as far as the user is
  // concerned nothing happened.
  if (!enabled) return SecurityScanLaunchOutcome.DISABLED
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
    val server = languageServerWrapper.languageServer
    val outcome = runSecurityScan(
      // A URI that does not resolve to a file on disk cannot be scanned, and a response could not
      // be matched back to it either, so it counts as "no editor".
      uri = if (path == null) null else uri,
      source = source,
      enabled = enabled,
      hasToken = tokenProviderManager.getToken().isNotBlank(),
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
    val entered = AtomicBoolean(false)
    val job = coroutineScope.launch {
      entered.set(true)
      // ONE coroutine, ONE lock region, both notifications in order. Splitting them across two
      // coroutines would only give mutual exclusion: a Mutex does not hand the lock out in the
      // order it was asked for, so the scan could overtake the configuration that enables it.
      outboundLock.withLock {
        if (!stillEnabled()) {
          // Switched off between the gate and the send. Drop the request and say nothing: the user
          // turning the feature off is the answer.
          if (waiterId != null) CommandWaiters.consumeById(waiterId, epoch)
          logger.info("Remote security scan was turned off before the request was sent.")
          return@withLock
        }
        // No server means nothing was sent; leaving the deadline unarmed lets the completion
        // handler below report it as a failure.
        val target = server ?: return@withLock
        target.didChangeConfiguration(DidChangeConfigurationParams(configurationService.buildParams()))
        target.runSecurityScan(params)
        if (waiterId != null) armDeadline(waiterId, epoch)
      }
    }
    // A cancelled scope makes `launch` return a completed job without throwing and without ever
    // running the body, so try/catch cannot see it. This can.
    job.invokeOnCompletion { reportIfNeverSent(waiterId, epoch, entered.get()) }
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
   * for the whole minute and postpone the "did this ever send" check by exactly that long.
   */
  private fun armDeadline(waiterId: Long, epoch: Long) {
    CommandWaiters.markDeadlineArmed(waiterId, epoch)
    coroutineScope.launch {
      delay(RESPONSE_DEADLINE_MS)
      // By id, never "the oldest": this request may already have been answered and a different
      // scan may be queued on the same file by now.
      if (CommandWaiters.consumeById(waiterId, epoch)) notify(TIMED_OUT_MESSAGE)
    }
  }

  /**
   * Runs when the sending coroutine finished, however it finished.
   *
   * An armed deadline means the request went out and is somebody else's problem now. Otherwise the
   * waiter is cancelled, and only a waiter that was still standing counts as a failure worth showing
   * — a request that was deliberately abandoned, or answered already, has removed its own.
   */
  private fun reportIfNeverSent(waiterId: Long?, epoch: Long, entered: Boolean) {
    if (waiterId != null && CommandWaiters.isDeadlineArmed(waiterId)) return
    if (!entered) logger.warn("A remote security scan request never started.")
    if (waiterId == null) return
    if (CommandWaiters.consumeById(waiterId, epoch)) {
      logger.warn("A remote security scan request was not sent.")
      notify(SEND_FAILED_MESSAGE)
    }
  }
}
