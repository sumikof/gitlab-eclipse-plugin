package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class OAuthTokenProvider : TokenProvider {
  private var currentToken: GitLabAuthorizationToken? = null
  private val logger by lazy { logger<OAuthTokenProvider>() }
  var scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)

  override fun getToken(): String {
    refreshTokenIfExpired()
    return currentToken?.accessToken.orEmpty()
  }

  fun updateToken(newToken: GitLabAuthorizationToken?) {
    this.currentToken = newToken
  }

  private fun refreshTokenIfExpired() {
    val tokenExpirationTimestamp = currentToken?.tokenExpirationTimestamp ?: Instant.MIN
    // Always check if the token is expired first
    if (tokenExpirationTimestamp > Instant.now()) return

    logger.info("Refreshing expired token with timestamp $tokenExpirationTimestamp.")

    val refreshedToken = service<GitLabOAuthService>().refreshToken(currentToken?.refreshToken.orEmpty())
    if (refreshedToken == null) {
      logger.info("Failed to refresh the OAuth token.")
      NotificationUtils.show("Failed to refresh the OAuth token. Please re-authenticate.")
      return
    }

    logger.info("The OAuth token has been refreshed and it expires at ${refreshedToken.tokenExpirationTimestamp}.")

    updateToken(refreshedToken)
  }

  fun startTokenRefreshTimer(
    refreshIntervalInSeconds: Int = currentToken?.expiresIn ?: DEFAULT_REFRESH_INTERVAL_SECONDS
  ) {
    if (currentToken == null) {
      logger.info("Canceling the timer for token refresh.")
      scheduler.shutdownNow()
      return
    }

    val refreshIntervalInMillis = Duration.ofSeconds(refreshIntervalInSeconds.toLong()).toMillis()

    scheduler.scheduleAtFixedRate({
      if (currentToken != null) {
        refreshTokenIfExpired()
        logger.info("Token refreshed by scheduled task.")
      }
    }, 0, refreshIntervalInMillis, TimeUnit.MILLISECONDS)
  }

  fun stopTokenRefreshTimer() {
    logger.info("Canceling the timer for token refresh.")
    scheduler.shutdownNow()
  }

  companion object {
    const val DEFAULT_REFRESH_INTERVAL_SECONDS: Int = 7200
  }
}
