package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.preferences.storage.SecretStorage
import com.gitlab.eclipse.utils.logger
import com.google.gson.GsonBuilder

class OAuthSecretStorage(
  private val secretStorage: SecretStorage = SecretStorage("gitlab.com")
) {
  private val logger by lazy { logger<OAuthSecretStorage>() }
  private val gson = GsonBuilder()
    .registerTypeAdapter(GitLabAuthorizationToken::class.java, GitLabAuthorizationTokenDeserializer())
    .create()

  companion object {
    const val SECRET_NAME = "oauth_token"
  }

  fun getOAuthToken(): GitLabAuthorizationToken? {
    val serializedOAuthToken = secretStorage.getSecret(SECRET_NAME).orEmpty()
    return if (serializedOAuthToken.isEmpty()) {
      logger.info("Loaded OAuth token is null.")
      null
    } else {
      val token = gson.fromJson(serializedOAuthToken, GitLabAuthorizationToken::class.java)
      logger.info("Loaded OAuth token expires at ${token.tokenExpirationTimestamp}")
      token
    }
  }

  fun setOAuthToken(token: GitLabAuthorizationToken?) {
    val jsonOAuthToken = gson.toJson(token)
    secretStorage.putSecret(SECRET_NAME, jsonOAuthToken)
  }
}
