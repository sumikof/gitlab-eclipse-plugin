package com.gitlab.eclipse.preferences.storage

import org.eclipse.core.runtime.Platform
import org.eclipse.equinox.security.storage.ISecurePreferences
import org.eclipse.equinox.security.storage.SecurePreferencesFactory
import org.eclipse.equinox.security.storage.StorageException

/**
 * Stubbed secret storage which supports environment variable resolution.
 * For formal support https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/issues/19
 * For Equo support https://gitlab.com/gitlab-org/editor-extensions/gitlab-eclipse-plugin/-/issues/20
 */
class SecretStorage(rootURI: String?) {
  private val node: ISecurePreferences = SecurePreferencesFactory.getDefault()
    .node("gitlab")
    .node("hosts")
    .node(rootURI)

  fun getSecret(key: String?) = try {
    node.get(key, null)
  } catch (e: StorageException) {
    Platform.getLog(javaClass).error(e.message, e)

    key?.split(Regex("[^a-zA-Z0-9]+"))
      ?.joinToString("_")
      ?.let { "GITLAB_ECLIPSE_${it.uppercase()}" }
      ?.let { System.getenv(it) }
  }

  @Throws(StorageException::class)
  fun putSecret(key: String?, value: String?) {
    try {
      node.put(key, value, true)
    } catch (e: StorageException) {
      Platform.getLog(javaClass).error(e.message, e)
    }
  }
}
