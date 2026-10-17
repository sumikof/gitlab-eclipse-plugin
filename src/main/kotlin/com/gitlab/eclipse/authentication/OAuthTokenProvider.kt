package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import org.eclipse.equinox.security.storage.StorageException
import org.eclipse.swt.widgets.Display
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Holds the OAuth token and refreshes it when it expires (design §8, §11).
 *
 * - Reads are lock-free. The first load from secure storage publishes with `compareAndSet(null, …)`
 *   only, so it never overwrites a token that is already published (including a refreshed one).
 * - Refreshes, authorization-flow updates and the drop after a rejection write under [refreshLock],
 *   so concurrent callers share one refresh instead of replaying the same refresh token.
 * - Notifications and configuration sends always run after [refreshLock] is released.
 * - After [stopTokenRefreshTimer] no notice opens and no queued configuration send goes out: each re-checks the
 *   stop when it runs (a notice in its UI turn, a send before it is built). Tokens are still stored after the
 *   stop (the refresh token is single use, and a sign-in's result is kept).
 * - [startTokenRefreshTimer] registers one fixed-delay check every [refreshCheckPeriod]. Each run refreshes
 *   the published token once it has expired, so it also picks up a token published after the timer started
 *   (the first load at startup, a later sign-in). The timer never reads secure storage: the first load stays
 *   on [getToken] / [hasToken] (at startup, the language server configuration build), so there is no second
 *   concurrent first load and the master-password prompt appears where it did before.
 * - The first load is single-flight per attempt: one thread reads secure storage (one master-password prompt,
 *   also on cancel), other non-UI callers wait for it and answer from the published state. The UI thread
 *   never waits: the prompt opens via `syncExec`, which the UI thread would never run while blocked on our
 *   latch (deadlock), so it answers "no token yet" at once.
 * - A first load that cannot read secure storage (e.g. the master-password prompt was cancelled) keeps OAuth
 *   selected, notifies once and is not retried until [updateToken]: the stored token may still be valid.
 *   An empty or corrupt one switches to PAT and re-arms the load, so selecting OAuth again reads it again.
 * - Every AUTHENTICATION_TYPE write takes [authTypeLock], so an empty or corrupt first load that a sign-in
 *   superseded does not switch to PAT after the sign-in selected OAuth.
 *
 * TooManyFunctions is suppressed: [hasToken] (design §7.3) has to read [currentToken] and the lock-free
 * first load directly, so it cannot move out of this class without exposing that state.
 */
@Suppress("TooManyFunctions")
class OAuthTokenProvider(
  private val languageServiceConfigurationService: GitLabLanguageServerConfigurationService = service(),
  private val preferenceStore: ScopedPreferenceStore = service(),
  private val oAuthSecretStorage: OAuthSecretStorage = OAuthSecretStorage(),
  // Resolved lazily: GitLabOAuthService itself looks up this provider (cycle).
  private val oAuthService: () -> GitLabOAuthService = { service() },
  private val clock: () -> Instant = Instant::now,
  private val notify: (message: String, gate: (show: () -> Unit) -> Unit) -> Unit =
    { message, gate -> NotificationUtils.show(message, gate = gate) },
  private val refreshCheckPeriod: Duration = REFRESH_CHECK_PERIOD,
  private val isUiThread: () -> Boolean = { Display.getCurrent() != null },
) : TokenProvider {
  private val currentToken = AtomicReference<GitLabAuthorizationToken?>(null)
  private val logger by lazy { logger<OAuthTokenProvider>() }

  /** Never taken on the UI thread; never held while notifying or sending the configuration. */
  private val refreshLock = Any()

  /** Guarded by [refreshLock]. No refresh before this instant after a transient failure. */
  private var retryNotBefore: Instant = Instant.MIN

  /** Guarded by [refreshLock]. Whether the current run of transient failures was already notified. */
  private var transientNotified = false

  /** Serializes registering the periodic task with [stopTokenRefreshTimer]. Independent of [refreshLock]. */
  private val timerLock = Any()

  /** Guarded by [timerLock]. The periodic check, once registered. */
  private var refreshTask: ScheduledFuture<*>? = null

  /**
   * Set by [stopTokenRefreshTimer]. From then on nothing sends the configuration or notifies, including effects
   * queued before (see [effectGate]): the language server and the workbench are being torn down. A refreshed
   * token is still stored, because the refresh token it replaced is single use; so is a sign-in's token. Sticky.
   */
  @Volatile private var stopped = false

  /**
   * Makes "check [stopped], then dispatch the effect" one step against [stopTokenRefreshTimer], so no effect
   * starts after the stop. Dispatches share the read lock, so they never exclude each other (an effect may
   * itself lead to another refresh); only the stop takes the write lock and waits for them. Held only while
   * dispatching: `sendConfigurationIf` queues a coroutine and the notification an `asyncExec`, so neither
   * waits. Never nested with [refreshLock] or [timerLock].
   *
   * Queued effects re-check [stopped] when they run (design §6):
   * - A notice re-checks it in its UI turn, under this read lock, around `popup.open()` (see [postNotice]).
   *   The UI thread only ever tries the read lock (`tryLock`), never waits for it: if the stop holds the write
   *   lock, nothing is shown. Otherwise the stop's write either precedes that region (nothing is shown) or
   *   follows it (the popup opened before the stop returned), so no OAuth popup opens after
   *   [stopTokenRefreshTimer] returns.
   * - A configuration send re-checks it inside the service's outbound lock, before building the
   *   configuration (`sendConfigurationIf { !stopped }`). The send is NOT wrapped in this lock: building the
   *   configuration can refresh the token over the network (up to the HTTP timeouts) and
   *   `didChangeConfiguration` can block on the language server's stdin, so a stop waiting for the send could
   *   hang. A send whose check passed before the stop is in flight and may still complete; it ends
   *   before the language server is stopped, or goes to a stopped server and is dropped by the send's catch.
   *
   * Invariants that keep the UI thread's read region safe (design §11 item 1, R-5):
   * - (a) The only writer, [stopTokenRefreshTimer], must never be reachable from a UI runnable while a notice
   *   runnable holds the read lock: a read→write upgrade on one thread deadlocks with itself.
   * - (b) `popup.open()` does not run Synchronizer messages (the popup opens non-blocking, no nested event
   *   loop), so the UI thread holds the read lock only briefly and no asyncExec/syncExec runnable runs inside it
   *   (a `timerExec` runnable may, which is harmless for the same reason as below). On GTK,
   *   `Shell.setVisible(true)` iterates the GLib main context until the shell maps; SWT runs async messages
   *   there only while a native dialog has installed its idle proc, i.e. only if the notice runnable itself
   *   runs nested inside a native dialog's loop. Harmless: such a nested runnable is never the writer, and with
   *   `tryLock` it never waits.
   * - (c) The lock is writer-preferring: once a stop is waiting, a waiting read waits for it, and so for every
   *   dispatch in progress. Every dispatch region must therefore stay non-blocking. This applies to the
   *   dispatch regions against the stop, not to the UI thread: its `tryLock` barges past a queued writer and
   *   fails only while the write lock is held, so it never waits. A waiting UI thread could deadlock during
   *   `Display.dispose()`: a dispatcher holding the read lock blocks in `asyncExec` on SWT's `Device.class`
   *   monitor, the UI thread holds that monitor while draining queued runnables and would wait for the read
   *   lock behind the queued stop, and the stop waits for the dispatcher.
   *   Known residual: a dispatch region may itself run on the UI thread when the UI thread claims the first
   *   load ([hasToken] is UI-safe) and the read fails, so the load-failure dispatch takes a waiting read there.
   *   Reaching the same deadlock needs that first load still unclaimed at shutdown (the language server
   *   configuration build normally claims it at startup) while the display is being disposed.
   *
   * Internal for tests only.
   */
  internal val effectGate = ReentrantReadWriteLock()

  /**
   * The current first-load attempt. Only the thread that claims it reads secure storage; the others wait for
   * its [LoadAttempt.done] (see [loadCachedIfAbsent]). Once it has failed to read secure storage, no first load
   * reads it again, so a cancelled master-password prompt is not shown again on every call. [updateToken]
   * replaces it with a fresh attempt and releases the old one's waiters, so a read that was already in flight
   * cannot mark the new one failed or notify after it. It does so after publishing the token, and a load
   * captures the attempt before checking the token, so no load can read storage on behalf of the fresh attempt.
   */
  private val loadAttempt = AtomicReference(LoadAttempt(failed = false))

  /**
   * Makes writing AUTHENTICATION_TYPE one step with publishing the token and the attempt, so a first load's
   * switch to PAT cannot interleave with a sign-in. Held only for in-memory checks and one preference write:
   * never across secure-storage I/O, the network, notifications or `sendConfiguration`, so it is safe on the
   * UI thread. `ScopedPreferenceStore.setValue` fires listeners synchronously on the writer's thread; no plugin
   * listener watches AUTHENTICATION_TYPE, and none may block (one doing `syncExec` would deadlock with the UI
   * thread holding this lock). Lock order: [refreshLock] → [authTypeLock] only.
   */
  private val authTypeLock = Any()

  /**
   * Daemon: the refresh is best effort, so its thread must never keep the JVM alive when
   * [stopTokenRefreshTimer] is not reached.
   */
  var scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1) { runnable ->
    Thread(runnable, REFRESH_THREAD_NAME).apply { isDaemon = true }
  }

  override fun getToken(): String {
    if (currentToken.get() == null) loadCachedIfAbsent()
    val token = currentToken.get() ?: return ""
    if (token.tokenExpirationTimestamp > clock()) return token.accessToken

    refreshIfExpired()
    // After a transient failure this is still the (buffer-expired) token: the server accepts it
    // until the real expiry, 120 s later.
    return currentToken.get()?.accessToken.orEmpty()
  }

  /**
   * Whether an OAuth token is available (design §7.3). An expired token counts: whoever sends the
   * request refreshes it. Never takes [refreshLock] and never refreshes, so it does not wait for an
   * in-flight refresh and is safe on the UI thread (design §11).
   */
  override fun hasToken(): Boolean {
    loadCachedIfAbsent()
    return !currentToken.get()?.accessToken.isNullOrEmpty()
  }

  /**
   * True while OAuth is selected, no token is published, and the first load is in flight or has failed.
   * [GitLabTokenProviderManager] then skips its PAT fallback: the PAT read goes to the same secure storage, so
   * it would prompt for the master password again or block (on the UI thread) on Equinox's storage lock behind
   * the in-flight read. Lock-free and without I/O, so safe on the UI thread.
   */
  internal fun isLoadPendingOrFailed(): Boolean {
    if (currentToken.get() != null || !isOAuthEnabled()) return false
    val attempt = loadAttempt.get()
    return attempt.failed || (attempt.loader.get() != null && attempt.done.count > 0)
  }

  fun updateToken(newToken: GitLabAuthorizationToken?) {
    synchronized(refreshLock) {
      synchronized(authTypeLock) {
        setOAuthInPreferenceStore(true)
        // Token first: a load that captures the fresh attempt then sees the token and does not read storage.
        currentToken.set(newToken)
        // Waiters on a read still in flight answer with this token now instead of waiting for a stale prompt.
        loadAttempt.getAndSet(LoadAttempt(failed = false)).done.countDown()
      }
      // May prompt for the master password: outside authTypeLock.
      oAuthSecretStorage.setOAuthToken(newToken)
      retryNotBefore = Instant.MIN
      transientNotified = false
    }
    // A session whose startup failed before it registered the timer still gets refreshes after a sign-in.
    // Idempotent, and a no-op after stop. Outside every lock.
    startTokenRefreshTimer()
    // The token above is stored even after a stop; only the send is dropped, here and again right before it
    // goes out (see [effectGate]).
    effectGate.read { if (!stopped) languageServiceConfigurationService.sendConfigurationIf { !stopped } }
  }

  /**
   * Dispatches a notice whose popup is shown only if, in its own UI turn, the provider has not stopped and
   * [stillRelevant] still holds. Both are checked under [effectGate]'s read lock, so the popup either opens
   * before [stopTokenRefreshTimer] returns or not at all. Call it while dispatching under that read lock, after
   * the dispatch-time checks.
   *
   * The UI turn never waits for the lock: `tryLock` barges past a queued stop (reentrant reads succeed too) and
   * fails only while the stop holds the write lock, in which case the stop is in progress and nothing is
   * shown. Waiting there could deadlock with SWT's `Device.class` monitor during `Display.dispose()` (see
   * [effectGate], invariant (c)).
   */
  private fun postNotice(message: String, stillRelevant: () -> Boolean = { true }) {
    notify(message) { show ->
      val readLock = effectGate.readLock()
      // The write lock is held: the stop is in progress, show nothing.
      if (!readLock.tryLock()) return@notify
      try {
        if (!stopped && stillRelevant()) show()
      } finally {
        readLock.unlock()
      }
    }
  }

  private fun setOAuthInPreferenceStore(value: Boolean) {
    val tokenProviderType = if (value) TokenProviderType.OAUTH.name else TokenProviderType.PAT.name
    preferenceStore.setValue(PreferenceConstants.AUTHENTICATION_TYPE, tokenProviderType)
  }

  /**
   * Loads the cached token from secure storage if none is published yet. Takes no lock; the read runs on the
   * caller's thread as before.
   *
   * Single-flight: the first caller to claim the attempt reads, and releases [LoadAttempt.done] whatever the
   * outcome. A later caller never reads for that attempt: on the claiming thread itself (re-entry) or on the
   * UI thread it returns at once, otherwise it waits without timeout (an interrupt ends the wait), and then
   * answers from the published state. The UI thread must not wait: the reader's master-password prompt runs
   * through `syncExec`, so a UI thread parked on our latch would deadlock with it.
   *
   * An empty or unparsable storage switches to PAT. A storage that cannot be read keeps OAuth (the token may
   * still be there), notifies once and stops further loads until [updateToken]. Either outcome is ignored
   * when a sign-in superseded the read while it was pending.
   */
  internal fun loadCachedIfAbsent() {
    // Captured before the read: the failure below only counts if no sign-in replaced this attempt meanwhile.
    val attempt = loadAttempt.get()
    if (currentToken.get() != null || attempt.failed || !isOAuthEnabled()) return

    if (!attempt.loader.compareAndSet(null, Thread.currentThread())) {
      awaitLoad(attempt)
      return
    }
    try {
      readCachedToken(attempt)
    } finally {
      // After any outcome (also an unexpected throwable), so no waiter is stranded.
      attempt.done.countDown()
    }
  }

  /** Waits for another thread's read of [attempt], except on its own loader thread or on the UI thread. */
  private fun awaitLoad(attempt: LoadAttempt) {
    if (attempt.loader.get() === Thread.currentThread() || isUiThread()) return
    try {
      attempt.done.await()
    } catch (e: InterruptedException) {
      logger.info("Interrupted while waiting for the first load: ${e::class.java.name}")
      Thread.currentThread().interrupt()
    }
  }

  /** The claimed read of [attempt] and its outcome. */
  private fun readCachedToken(attempt: LoadAttempt) {
    val cachedToken = try {
      oAuthSecretStorage.getOAuthToken()
    } catch (e: StorageException) {
      logger.info("Failed to read the cached token from secure storage: ${e::class.java.name}")
      // Only this attempt's loader reads; the CAS fails when a sign-in (or a re-arm) replaced the attempt.
      val failedAttempt = LoadAttempt(failed = true)
      if (loadAttempt.compareAndSet(attempt, failedAttempt)) {
        // Rechecked at dispatch: a sign-in that replaced the attempt after the CAS makes the notice obsolete.
        // The token check is defensive: single-flight leaves no other load to publish one.
        effectGate.read {
          val current = loadAttempt.get() === failedAttempt && currentToken.get() == null
          if (!stopped && current) {
            // Rechecked again in the notice's UI turn: a sign-in while it is queued makes it obsolete too.
            postNotice(LOAD_FAILURE_MESSAGE) { loadAttempt.get() === failedAttempt && currentToken.get() == null }
          }
        }
      }
      return
    } catch (e: Exception) {
      logger.info("Failed to load cached token: ${e::class.java.name}")
      switchToPatAndRearmUnlessSuperseded(attempt)
      return
    }

    if (cachedToken == null) {
      logger.info(
        "No cached token found in PasswordSafe. Updating settings to reflect that OAuth is no longer enabled."
      )
      switchToPatAndRearmUnlessSuperseded(attempt)
      return
    }

    if (currentToken.compareAndSet(null, cachedToken)) {
      logger.info(
        "Loading cached token from PasswordSafe. Expiration timestamp is ${cachedToken.tokenExpirationTimestamp}"
      )
    }
  }

  /**
   * Switches to PAT and replaces [attempt] with a fresh one, so selecting OAuth again reads storage again;
   * neither happens if a sign-in replaced [attempt] or a token is published meanwhile.
   */
  private fun switchToPatAndRearmUnlessSuperseded(attempt: LoadAttempt) {
    synchronized(authTypeLock) {
      if (loadAttempt.get() === attempt && currentToken.get() == null) {
        setOAuthInPreferenceStore(false)
        // After the PAT write: a load that captures the fresh attempt also sees PAT and does not read storage
        // a second time (for corrupt storage, a second decrypt = a second prompt).
        loadAttempt.compareAndSet(attempt, LoadAttempt(failed = false))
      }
    }
  }

  /**
   * After a loaded token was dropped (with PAT selected): a fresh attempt, so selecting OAuth again reads
   * storage again as before single-flight (the done attempt would answer "no token" until a sign-in). Called
   * under [authTypeLock], after any PAT write, for the same reason as in [switchToPatAndRearmUnlessSuperseded].
   */
  private fun rearmLoad() {
    loadAttempt.getAndSet(LoadAttempt(failed = false)).done.countDown()
  }

  /**
   * Refreshes at most once across concurrent callers. The decision, the network call and the state
   * writes happen under [refreshLock]; the outcome's effect (notification or configuration send)
   * runs only after the lock is released, and not after [stopTokenRefreshTimer].
   *
   * @return whether this call refreshed the token.
   */
  private fun refreshIfExpired(): Boolean {
    var refreshed = false
    val effect = synchronized<(() -> Unit)?>(refreshLock) {
      // Null after a rejection: nothing left to refresh.
      val token = currentToken.get() ?: return@synchronized null
      val now = clock()
      // Another caller refreshed it while we waited, or a transient failure is backing off.
      if (token.tokenExpirationTimestamp > now || now < retryNotBefore) return@synchronized null
      // A late first load re-published a rejected token, or the user switched to PAT (design N1).
      if (!isOAuthEnabled()) {
        currentToken.set(null)
        synchronized(authTypeLock) { rearmLoad() }
        return@synchronized null
      }

      logger.info("Refreshing expired token with timestamp ${token.tokenExpirationTimestamp}.")

      when (val outcome = oAuthService().refreshToken(token.refreshToken)) {
        is RefreshOutcome.Refreshed -> {
          val refreshedToken = outcome.token
          currentToken.set(refreshedToken)
          oAuthSecretStorage.setOAuthToken(refreshedToken)
          retryNotBefore = Instant.MIN
          transientNotified = false
          logger.info(
            "The OAuth token has been refreshed and it expires at ${refreshedToken.tokenExpirationTimestamp}."
          )
          refreshed = true
          val send: () -> Unit = { languageServiceConfigurationService.sendConfigurationIf { !stopped } }
          send
        }

        is RefreshOutcome.Rejected -> {
          // Drop the dead token so it is not refreshed again; secure storage keeps it until re-authentication.
          currentToken.set(null)
          retryNotBefore = Instant.MIN
          transientNotified = false
          logger.info("Failed to refresh the OAuth token: Rejected(${outcome.error}).")
          synchronized(authTypeLock) {
            setOAuthInPreferenceStore(false)
            rearmLoad()
          }
          val reauthenticateNotice: () -> Unit = { postNotice(REAUTHENTICATE_MESSAGE) }
          reauthenticateNotice
        }

        is RefreshOutcome.Transient -> {
          retryNotBefore = clock().plus(RETRY_BACKOFF)
          logger.info(
            "Failed to refresh the OAuth token: Transient(${outcome.reason}). Retrying after $retryNotBefore."
          )
          if (transientNotified) {
            null
          } else {
            transientNotified = true
            val transientNotice: () -> Unit = { postNotice(TRANSIENT_FAILURE_MESSAGE) }
            transientNotice
          }
        }
      }
    }
    // Checked after the network call: a refresh that was in flight when the timer stopped has no effect.
    effectGate.read { if (!stopped) effect?.invoke() }
    return refreshed
  }

  /**
   * Registers the periodic refresh check once. Later calls, and calls after [stopTokenRefreshTimer],
   * do nothing. Fixed delay: a refresh stalled by its network timeouts never causes a burst of runs.
   */
  fun startTokenRefreshTimer() {
    synchronized(timerLock) {
      if (refreshTask != null || scheduler.isShutdown) return
      refreshTask = scheduler.scheduleWithFixedDelay(
        { runScheduledRefresh() },
        0,
        refreshCheckPeriod.toMillis(),
        TimeUnit.MILLISECONDS,
      )
    }
  }

  /**
   * The scheduled task body, off the UI thread. Handles only a token already published by [getToken],
   * [hasToken] or [updateToken]; it never reads secure storage, so it cannot race their first load into a
   * second master-password prompt or a conflicting switch to PAT. Takes [refreshLock] only once the token
   * has expired. Does nothing once [stopTokenRefreshTimer] has run. A run already past that check still
   * finishes its refresh and stores the token, but dispatches no effect after the stop. Catches
   * everything: an escaping throwable would cancel all later runs.
   */
  internal fun runScheduledRefresh() {
    try {
      if (stopped) return
      val token = currentToken.get() ?: return
      if (token.tokenExpirationTimestamp > clock()) return

      if (refreshIfExpired()) logger.info("Token refreshed by scheduled task.")
    } catch (t: Throwable) {
      // Type name only: an exception message may carry a response body.
      logger.warn("Scheduled OAuth refresh failed: ${t::class.java.name}")
    }
  }

  private fun isOAuthEnabled(): Boolean =
    preferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE) == TokenProviderType.OAUTH.name

  /**
   * Stops the periodic check and every effect not yet run: once this returns, no notice is shown and no
   * configuration send whose check had not passed yet is sent (see [effectGate]). Token persistence continues:
   * a refresh in flight still stores its token, and [updateToken] still stores and publishes one. Idempotent.
   *
   * The only writer of [effectGate]: it waits for dispatches in progress and for a notice's UI-thread check
   * and `popup.open()` that already holds the read lock. The UI thread never waits for it in turn (the notice
   * only tries the read lock), so the stop cannot be part of a wait cycle through the UI thread, such as one
   * with SWT's `Device.class` monitor during `Display.dispose()`. Must never be called from a UI runnable while
   * a notice runnable holds the read lock (read→write upgrade deadlock); the stop holds the write lock for one
   * assignment only.
   */
  fun stopTokenRefreshTimer() {
    logger.info("Canceling the timer for token refresh.")
    // Waits for an effect being dispatched or a notice being shown, so none starts after this returns.
    effectGate.write { stopped = true }
    // Not shutdownNow: interrupting a refresh in flight could fail storing its new token, and the old
    // refresh token is single use. Shutdown already drops the periodic task.
    synchronized(timerLock) { scheduler.shutdown() }
  }

  /** One first-load attempt, compared by identity. */
  private class LoadAttempt(val failed: Boolean) {
    /** The thread reading storage for this attempt, once claimed. */
    val loader = AtomicReference<Thread?>(null)

    /**
     * Released when the claimed read has finished (any outcome), or when [updateToken] or [rearmLoad] replaces
     * the attempt.
     */
    val done = CountDownLatch(1)
  }

  companion object {
    /**
     * How often the scheduled task checks the token. The first run that sees the buffered expiry comes at
     * most one period after it. A failed refresh takes at most 40 s (10 s connect + 30 s read timeout) and
     * the retry waits one period, which also covers [RETRY_BACKOFF] because the delay starts after
     * `retryNotBefore` is set. So a retry starts within 30 + 40 + 30 = 100 s of the buffered expiry, before
     * the real expiry [TOKEN_EXPIRATION_BUFFER_SECONDS] (120 s) later.
     */
    val REFRESH_CHECK_PERIOD: Duration = Duration.ofSeconds(30)

    private const val REFRESH_THREAD_NAME = "gitlab-oauth-refresh"

    /** How long to wait after a transient refresh failure before trying again. */
    val RETRY_BACKOFF: Duration = Duration.ofSeconds(30)

    private const val REAUTHENTICATE_MESSAGE = "Failed to refresh the OAuth token. Please re-authenticate."
    private const val TRANSIENT_FAILURE_MESSAGE = "Could not refresh the GitLab OAuth token. Retrying automatically."
    private const val LOAD_FAILURE_MESSAGE =
      "Could not read the GitLab OAuth token from secure storage. Restart Eclipse or sign in again."
  }
}
