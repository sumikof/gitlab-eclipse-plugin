package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.preferences.storage.InstanceSecretStore

/** The personal access token saved for a GitLab instance; never one saved for another instance. */
class PatTokenProvider(private val store: InstanceSecretStore = service()) : TokenProvider {
  override fun getToken(instanceUrl: String): String =
    store.storageFor(instanceUrl)?.getSecret(SECRET_NAME).orEmpty()

  override fun hasToken(instanceUrl: String): Boolean = getToken(instanceUrl).isNotEmpty()

  companion object {
    const val SECRET_NAME = "personal_access_token"
  }
}
