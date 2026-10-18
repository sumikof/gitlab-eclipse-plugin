package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.preferences.storage.InstanceSecretStore
import com.gitlab.eclipse.preferences.storage.SecretStorage
import com.gitlab.eclipse.utils.logger
import com.google.gson.GsonBuilder
import org.eclipse.equinox.security.storage.StorageException

/**
 * The OAuth token in secure storage, under the instance that issued it ([GitLabOAuthService.ISSUER_KEY]).
 *
 * @param secretStorage resolved on every call, not at construction: opening instance-scoped storage first runs the
 *   legacy token migration, which must not run while Koin builds the provider (possibly on the UI thread).
 */
class OAuthSecretStorage(
  private val secretStorage: () -> SecretStorage = {
    service<InstanceSecretStore>().storageFor(GitLabOAuthService.ISSUER_KEY)
  },
) {
  constructor(secretStorage: SecretStorage) : this({ secretStorage })

  private val logger by lazy { logger<OAuthSecretStorage>() }
  private val gson = GsonBuilder()
    .registerTypeAdapter(GitLabAuthorizationToken::class.java, GitLabAuthorizationTokenDeserializer())
    .create()

  companion object {
    const val SECRET_NAME = "oauth_token"
  }

  /**
   * The stored token, or null when secure storage holds none.
   *
   * @throws StorageException when secure storage cannot be read (e.g. the master-password prompt was
   *   cancelled), so the caller does not mistake it for an empty storage.
   */
  @Throws(StorageException::class)
  fun getOAuthToken(): GitLabAuthorizationToken? {
    val serializedOAuthToken = secretStorage().readSecret(SECRET_NAME).orEmpty()
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
    secretStorage().putSecret(SECRET_NAME, jsonOAuthToken)
  }
}
