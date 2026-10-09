package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the OAuth token and refreshes it when it expires (design §8, §11).
 *
 * - Reads are lock-free. The first load from secure storage publishes with `compareAndSet(null, …)`
 *   only, so it never overwrites a token that is already published (including a refreshed one).
 * - Refreshes, authorization-flow updates and the drop after a rejection write under [refreshLock],
 *   so concurrent callers share one refresh instead of replaying the same refresh token.
 * - Notifications and `sendConfiguration` always run after [refreshLock] is released.
 * - [startTokenRefreshTimer] registers one fixed-delay check every [refreshCheckPeriod]. Each run refreshes
 *   the published token once it has expired, so it also picks up a token published after the timer started
 *   (the first load at startup, a later sign-in). The timer never reads secure storage: the first load stays
 *   on [getToken] / [hasToken] (at startup, the language server configuration build), so there is no second
 *   concurrent first load and the master-password prompt appears where it did before.
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
  private val notify: (String) -> Unit = NotificationUtils::show,
  private val refreshCheckPeriod: Duration = REFRESH_CHECK_PERIOD,
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

  fun updateToken(newToken: GitLabAuthorizationToken?) {
    synchronized(refreshLock) {
      setOAuthInPreferenceStore(true)
      currentToken.set(newToken)
      oAuthSecretStorage.setOAuthToken(newToken)
      retryNotBefore = Instant.MIN
      transientNotified = false
    }
    languageServiceConfigurationService.sendConfiguration()
  }

  private fun setOAuthInPreferenceStore(value: Boolean) {
    val tokenProviderType = if (value) TokenProviderType.OAUTH.name else TokenProviderType.PAT.name
    preferenceStore.setValue(PreferenceConstants.AUTHENTICATION_TYPE, tokenProviderType)
  }

  /**
   * Loads the cached token from secure storage if none is published yet. Takes no lock, so it is
   * safe on the UI thread; the read runs on the caller's thread as before.
   */
  internal fun loadCachedIfAbsent() {
    if (currentToken.get() != null || !isOAuthEnabled()) return

    val cachedToken = try {
      oAuthSecretStorage.getOAuthToken()
    } catch (e: Exception) {
      logger.info("Failed to load cached token: ${e::class.java.name}")
      setOAuthInPreferenceStore(false)
      return
    }

    if (cachedToken == null) {
      logger.info(
        "No cached token found in PasswordSafe. Updating settings to reflect that OAuth is no longer enabled."
      )
      setOAuthInPreferenceStore(false)
      return
    }

    if (currentToken.compareAndSet(null, cachedToken)) {
      logger.info(
        "Loading cached token from PasswordSafe. Expiration timestamp is ${cachedToken.tokenExpirationTimestamp}"
      )
    }
  }

  /**
   * Refreshes at most once across concurrent callers. The decision, the network call and the state
   * writes happen under [refreshLock]; the outcome's effect (notification or `sendConfiguration`)
   * runs only after the lock is released.
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
          languageServiceConfigurationService::sendConfiguration
        }

        is RefreshOutcome.Rejected -> {
          // Drop the dead token so it is not refreshed again; secure storage keeps it until re-authentication.
          currentToken.set(null)
          retryNotBefore = Instant.MIN
          transientNotified = false
          logger.info("Failed to refresh the OAuth token: Rejected(${outcome.error}).")
          setOAuthInPreferenceStore(false)
          val reauthenticateNotice: () -> Unit = { notify(REAUTHENTICATE_MESSAGE) }
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
            val transientNotice: () -> Unit = { notify(TRANSIENT_FAILURE_MESSAGE) }
            transientNotice
          }
        }
      }
    }
    effect?.invoke()
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
   * has expired. Catches everything: an escaping throwable would cancel all later runs.
   */
  internal fun runScheduledRefresh() {
    try {
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

  fun stopTokenRefreshTimer() {
    logger.info("Canceling the timer for token refresh.")
    synchronized(timerLock) { scheduler.shutdownNow() }
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
  }
}
