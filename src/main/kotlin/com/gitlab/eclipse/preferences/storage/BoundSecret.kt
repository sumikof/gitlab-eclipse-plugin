package com.gitlab.eclipse.preferences.storage

/**
 * A secret field bound to the instance URL being edited next to it (#115, design §4.6): it shows the secret saved
 * for that instance and saves back under that instance only. SWT-free, so the preference page's field editor
 * delegates to it.
 *
 * Nothing is written unless the text differs from what was loaded for the same instance. A text loaded for one
 * instance is therefore never saved under another, and pressing OK without touching the field writes nothing.
 */
class BoundSecret(
  private val storageFor: (String?) -> SecretStorage?,
  private val name: String,
) {
  private var loaded = false
  private var loadedKey: GitLabInstanceKey? = null
  private var loadedValue = ""

  /** The secret saved for [instanceUrl]'s instance, or "" when there is none or the URL names no instance. */
  fun load(instanceUrl: String?): String {
    // Recorded before the read: it may open the master-password dialog, whose nested event loop can ask
    // [needsReload] again for this same instance, which must not start a second read (and a second prompt).
    loaded = true
    loadedKey = GitLabInstanceKey.of(instanceUrl)
    val value = storageFor(instanceUrl)?.getSecret(name).orEmpty()
    loadedValue = value
    return value
  }

  /** Whether [instanceUrl] names another instance than the one loaded (another spelling of it does not). */
  fun needsReload(instanceUrl: String?): Boolean = !loaded || GitLabInstanceKey.of(instanceUrl) != loadedKey

  /** Saves [text] for [instanceUrl]'s instance if it was changed there; an empty text removes the secret. */
  fun store(instanceUrl: String?, text: String) {
    if (needsReload(instanceUrl) || text == loadedValue) return
    val storage = storageFor(instanceUrl) ?: return
    if (text.isEmpty()) storage.removeSecret(name) else storage.putSecret(name, text)
    loadedValue = text
  }
}
