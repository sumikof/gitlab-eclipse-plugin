package com.gitlab.eclipse.preferences.storage

import com.gitlab.eclipse.utils.logger
import org.eclipse.equinox.security.storage.ISecurePreferences
import org.eclipse.equinox.security.storage.SecurePreferencesFactory
import org.eclipse.equinox.security.storage.StorageException

/**
 * Stubbed secret storage which supports environment variable resolution.
 * For formal support https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/issues/19
 * For Equo support https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/issues/20
 */
class SecretStorage(rootURI: String?) {
  private val logger = logger<SecretStorage>()

  private val node: ISecurePreferences = SecurePreferencesFactory.getDefault()
    .node("gitlab")
    .node("hosts")
    .node(rootURI)

  fun getSecret(key: String?) = try {
    node.get(key, null)
  } catch (e: StorageException) {
    logger.error(e.message, e)

    envSecret(key)
  }

  /**
   * Like [getSecret], but a secure storage failure (e.g. the master-password prompt was cancelled) is not
   * reported as a missing secret: without the environment variable fallback, the [StorageException] is
   * rethrown, so the caller can tell "unreadable" from "empty".
   */
  @Throws(StorageException::class)
  fun readSecret(key: String?): String? = try {
    node.get(key, null)
  } catch (e: StorageException) {
    // Type name only: the message is not needed to diagnose a failed read.
    logger.warn("Failed to read a secret from secure storage: ${e::class.java.name}")
    envSecret(key) ?: throw e
  }

  private fun envSecret(key: String?): String? =
    key?.split(Regex("[^a-zA-Z0-9]+"))
      ?.joinToString("_")
      ?.let { "GITLAB_ECLIPSE_${it.uppercase()}" }
      ?.let { System.getenv(it) }

  @Throws(StorageException::class)
  fun putSecret(key: String?, value: String?) {
    try {
      node.put(key, value, true)
    } catch (e: StorageException) {
      logger.error(e.message, e)
    }
  }
}
