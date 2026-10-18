package com.gitlab.eclipse.preferences.storage

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.utils.NotificationUtils
import org.eclipse.equinox.security.storage.EncodingUtils
import org.eclipse.equinox.security.storage.ISecurePreferences
import org.eclipse.equinox.security.storage.SecurePreferencesFactory
import org.eclipse.equinox.security.storage.StorageException
import org.eclipse.ui.preferences.ScopedPreferenceStore

/**
 * Secure storage scoped to one GitLab instance: `gitlab/instances/<canonical instance key>`. A token is stored
 * and read under the key of the instance it is sent to, so changing the instance URL never hands one instance's
 * token to another.
 *
 * @param root the `gitlab` secure storage node, resolved on first use.
 * @param configuredInstanceUrl the configured `gitlab.url`.
 */
class InstanceSecretStore(
  root: () -> ISecurePreferences = { SecurePreferencesFactory.getDefault().node(ROOT_NODE) },
  configuredInstanceUrl: () -> String = {
    service<ScopedPreferenceStore>().getString(PreferenceConstants.GITLAB_INSTANCE_URL)
  },
  notify: (String) -> Unit = { NotificationUtils.show(it) },
) {
  private val root by lazy(root)
  private val migration by lazy { LegacySecretMigration(this.root, configuredInstanceUrl, notify) }

  /** The storage of [instanceUrl]'s instance, or null when the URL cannot name one (no token is used then). */
  fun storageFor(instanceUrl: String?): SecretStorage? = GitLabInstanceKey.of(instanceUrl)?.let(::storageFor)

  fun storageFor(key: GitLabInstanceKey): SecretStorage {
    migration.ensure()
    val node = root.node(INSTANCES_NODE).node(EncodingUtils.encodeSlashes(key.canonical))
    return SecretStorage(node, ::guardAbsentRead)
  }

  /**
   * While this session's migration has failed, a token the instance does not hold yet may still sit in the legacy
   * node: report it as unreadable rather than absent, so the OAuth first load keeps OAuth selected instead of
   * switching to PAT (design §4.3, review F1). Tokens the instance holds stay readable, and writes are not affected.
   */
  private fun guardAbsentRead() {
    if (migration.failed) throw StorageException(StorageException.NO_PASSWORD, "token migration pending")
  }

  companion object {
    const val ROOT_NODE = "gitlab"
    const val INSTANCES_NODE = "instances"
  }
}
