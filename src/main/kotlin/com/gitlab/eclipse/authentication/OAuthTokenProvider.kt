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
import java.util.concurrent.TimeUnit

class OAuthTokenProvider(
  private val languageServiceConfigurationService: GitLabLanguageServerConfigurationService = service(),
  private val preferenceStore: ScopedPreferenceStore = service(),
  private val oAuthSecretStorage: OAuthSecretStorage = OAuthSecretStorage()
) : TokenProvider {
  private var currentToken: GitLabAuthorizationToken? = null
  private val logger by lazy { logger<OAuthTokenProvider>() }

  var scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)

  override fun getToken(): String {
    if (currentToken == null) {
      loadCachedToken()
    } else {
      refreshTokenIfExpired()
    }

    return currentToken?.accessToken.orEmpty()
  }

  fun updateToken(newToken: GitLabAuthorizationToken?) {
    setOAuthInPreferenceStore(true)
    this.currentToken = newToken
    oAuthSecretStorage.setOAuthToken(currentToken)
    languageServiceConfigurationService.sendConfiguration()
  }

  private fun setOAuthInPreferenceStore(value: Boolean) {
    val tokenProviderType = if (value) TokenProviderType.OAUTH.name else TokenProviderType.PAT.name
    preferenceStore.setValue(PreferenceConstants.AUTHENTICATION_TYPE, tokenProviderType)
  }

  private fun loadCachedToken() {
    if (currentToken != null || !isOAuthEnabled()) return

    try {
      oAuthSecretStorage.getOAuthToken()?.let { cachedToken ->
        logger.info(
          "Loading cached token from PasswordSafe. Expiration timestamp is ${cachedToken.tokenExpirationTimestamp}"
        )
        currentToken = cachedToken
        refreshTokenIfExpired()
      } ?: run {
        logger.info(
          "No cached token found in PasswordSafe. Updating settings to reflect that OAuth is no longer enabled."
        )
        setOAuthInPreferenceStore(false)
      }
    } catch (e: Exception) {
      logger.info("Failed to load cached token: ${e.message}")
      setOAuthInPreferenceStore(false)
    }
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
      setOAuthInPreferenceStore(false)
      return
    }

    logger.info("The OAuth token has been refreshed and it expires at ${refreshedToken.tokenExpirationTimestamp}.")

    updateToken(refreshedToken)
  }

  fun startTokenRefreshTimer(
    refreshIntervalInSeconds: Int = currentToken?.expiresIn ?: DEFAULT_REFRESH_INTERVAL_SECONDS
  ) {
    if (!isOAuthEnabled()) {
      return
    }

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

  private fun isOAuthEnabled(): Boolean =
    preferenceStore.getString(PreferenceConstants.AUTHENTICATION_TYPE) == TokenProviderType.OAUTH.name

  fun stopTokenRefreshTimer() {
    logger.info("Canceling the timer for token refresh.")
    scheduler.shutdownNow()
  }

  companion object {
    const val DEFAULT_REFRESH_INTERVAL_SECONDS: Int = 7200
  }
}
