package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import java.time.Instant

class OAuthTokenProvider : TokenProvider {
  private var currentToken: GitLabAuthorizationToken? = null

  private val logger by lazy { logger<OAuthTokenProvider>() }

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
}
