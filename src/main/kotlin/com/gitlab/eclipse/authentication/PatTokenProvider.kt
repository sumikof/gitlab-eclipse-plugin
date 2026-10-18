package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.preferences.storage.InstanceSecretStore
import org.eclipse.ui.preferences.ScopedPreferenceStore

/** The personal access token saved for a GitLab instance; never one saved for another instance. */
class PatTokenProvider(private val store: InstanceSecretStore = service()) : TokenProvider {
  fun getToken(instanceUrl: String): String =
    store.storageFor(instanceUrl)?.getSecret(SECRET_NAME).orEmpty()

  fun hasToken(instanceUrl: String): Boolean = getToken(instanceUrl).isNotEmpty()

  override fun getToken(): String = getToken(configuredInstanceUrl())

  override fun hasToken(): Boolean = getToken().isNotEmpty()

  private fun configuredInstanceUrl(): String =
    service<ScopedPreferenceStore>().getString(PreferenceConstants.GITLAB_INSTANCE_URL)

  companion object {
    const val SECRET_NAME = "personal_access_token"
  }
}
