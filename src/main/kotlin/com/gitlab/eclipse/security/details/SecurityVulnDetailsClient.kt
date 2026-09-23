package com.gitlab.eclipse.security.details

import com.gitlab.eclipse.lsp.LanguageServerHandle
import com.gitlab.eclipse.lsp.plugins.messages.ExtensionToPluginNotification
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shown when the cursor line has no retained finding that can be displayed. */
internal const val NO_FINDING_MESSAGE =
  "No GitLab security finding on this line. Run a remote scan (SAST) on the file first."

/** Shown when the file's findings changed between the read and the send, so what was read is not sent. */
internal const val STALE_MESSAGE = "The security findings for this file changed. Run the command again."

/** Shown when the finding was sent but the tab could not be opened. */
internal const val OPEN_FAILED_MESSAGE = "Could not open the GitLab vulnerability details. See the Error Log."

private const val PLUGIN_ID = "security-vuln-details"
private const val UPDATE_DETAILS = "updateDetails"

/**
 * Shows the retained scan finding on one editor line in the `security-vuln-details` webview (design §9.2
 * steps 4–8): read → select → project → send → open the tab.
 *
 * Touches neither SWT nor the workbench: the tab is opened by the `openTab` the caller passes, on the UI
 * thread, through [onUiThread]. So everything here runs in a headless test.
 *
 * **Heavy work outside the lock.** Reading, selecting and projecting — the projection escapes the whole
 * untrusted finding, so its cost grows with the finding — all happen before [outboundLock] is taken. The
 * lock is the one every configuration send and scan request also waits on, and a huge description must
 * not stall them. Under it happen exactly two things: a re-read that must return the **same instance**
 * as the first read, and the send. The intake's snapshots are immutable and replaced wholesale, so
 * reference identity proves the file's findings did not move since they were projected, and holding the
 * lock across the check and the send is what binds the two (A22). The re-read takes the registry monitor
 * while the lock is held, which is the permitted order.
 *
 * **Bound to one connection.** The proxy and the epoch come from the one [LanguageServerHandle] the caller
 * read; the wrapper is never read again, so a reconnect in the middle can neither redirect the send nor
 * pair this connection's proxy with another's findings.
 *
 * **Never breaks the shared scope.** [coroutineScope] is the Koin one, built on a plain `Job`
 * (`WorkspaceModule`): one escaping failure cancels it for the rest of the session. The whole body is
 * therefore contained the same way `SecurityScanLauncher.contained` is — cancellation is rethrown,
 * anything else is caught and logged by class name only, since a message could quote the path or the
 * finding (§15).
 *
 * A send that returns but never reaches the webview cannot be detected: there is no acknowledgement
 * (design §12, a stated limit).
 *
 * @property coroutineScope where the work runs; the shared Koin scope in production
 * @property outboundLock the outbound `Mutex` (`LANGUAGE_SERVER_OUTBOUND`)
 * @property read the intake read; returns the stored immutable snapshot or `null`
 * @property project turns one raw finding into the escaped object the webview renders, or `null`
 * @property onUiThread hops to the UI thread without waiting; `asyncExec` in production
 * @property notify tells the user something; `NotificationUtils.show`, which marshals for itself
 */
class SecurityVulnDetailsClient(
  private val coroutineScope: CoroutineScope,
  private val outboundLock: Mutex,
  private val read: (String, Long) -> FileVulnerabilities? = { path, epoch ->
    VulnerabilityRegistry.intake.read(path, epoch)
  },
  private val project: (Any?) -> Map<String, Any?>? = { VulnerabilityProjection.of(it) },
  private val onUiThread: (Runnable) -> Unit = { currentDisplay.asyncExec(it) },
  private val notify: (String) -> Unit = { NotificationUtils.show(it) },
) {
  private val log by lazy { logger<SecurityVulnDetailsClient>() }

  /**
   * Shows the finding on [line] (1-based) of [path] from the findings retained for [handle]'s connection,
   * then opens the tab with [openTab].
   *
   * Called on the UI thread and returns at once: all the work runs on [coroutineScope], and the UI thread
   * never waits for it. Nothing is opened when there is nothing to show, when the findings changed in
   * between, or when the send failed.
   */
  fun show(handle: LanguageServerHandle, path: String, line: Int, openTab: () -> Unit) {
    coroutineScope.launch {
      contained { showDetails(handle, path, line, openTab) }
        ?.let { failure -> logQuietly("Failed to show vulnerability details: $failure") }
    }
  }

  private suspend fun showDetails(handle: LanguageServerHandle, path: String, line: Int, openTab: () -> Unit) {
    // Everything that costs something, before the lock.
    val snapshot = read(path, handle.connectionEpoch)
    val projected = snapshot?.let { VulnerabilityLookup.at(it, line) }?.let(project)
    if (snapshot == null || projected == null) {
      notify(NO_FINDING_MESSAGE)
      return
    }
    val payload = VulnerabilityPayload.of(projected, path, snapshot.timestampMillis)

    val sent = outboundLock.withLock {
      if (read(path, handle.connectionEpoch) !== snapshot) {
        false
      } else {
        handle.proxy.pluginNotification(ExtensionToPluginNotification(PLUGIN_ID, UPDATE_DETAILS, payload))
        true
      }
    }
    if (!sent) {
      notify(STALE_MESSAGE)
      return
    }

    // Only once the lock is released: never hop to the UI thread while holding it.
    onUiThread(Runnable { openContained(openTab) })
  }

  /**
   * Runs [openTab] on the UI thread so that nothing it throws reaches the event loop. Catches [Error] as
   * well: `SWTError` is one, and an escape here becomes an "Unhandled event loop exception".
   */
  @Suppress("TooGenericExceptionCaught")
  private fun openContained(openTab: () -> Unit) {
    try {
      openTab()
    } catch (e: Throwable) {
      logQuietly("Failed to open the vulnerability details: ${e::class.simpleName ?: e.javaClass.name}")
      runCatching { notify(OPEN_FAILED_MESSAGE) }
    }
  }

  /** The platform log can be gone while the workbench stops; losing the line must not escape. */
  private fun logQuietly(message: String) {
    runCatching { log.warn(message) }
  }

  /**
   * Runs [body] so that only cancellation escapes, like `SecurityScanLauncher.contained`.
   *
   * @return the class name of what [body] threw, or `null` if it finished. Only the name, never the
   *   message: an lsp4j failure can quote the request, which carries the finding.
   */
  @Suppress("TooGenericExceptionCaught")
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
}
