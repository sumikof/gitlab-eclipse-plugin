package com.gitlab.eclipse.preferences.storage

import com.gitlab.eclipse.utils.logger
import org.eclipse.equinox.security.storage.EncodingUtils
import org.eclipse.equinox.security.storage.ISecurePreferences
import org.eclipse.equinox.security.storage.StorageException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Moves the tokens of the host-less legacy node `gitlab/hosts/gitlab.com` to an instance-scoped node, once
 * (design §4.3, #115).
 *
 * The legacy node does not record which instance its tokens belong to. They are moved to gitlab.com only when
 * gitlab.com is the configured instance at the time of the decision; for any other instance they are left where
 * they are (never decrypted, never used) and the user is told to enter the token again. The decision is recorded
 * in an unencrypted marker, so switching to gitlab.com later does not move a self-managed token there. The legacy
 * node is kept for a rollback to an earlier version.
 *
 * Lock-free and idempotent: concurrent runs copy the same values to the same keys, and a run never overwrites a
 * token the instance node already holds. A plugin lock here could deadlock the UI thread against a master-password
 * prompt (`syncExec`) raised under it.
 */
internal class LegacySecretMigration(
  private val root: ISecurePreferences,
  private val configuredInstanceUrl: () -> String,
  private val notify: (String) -> Unit,
) {
  private val logger by lazy { logger<LegacySecretMigration>() }

  /** Set once this session has run (whatever the outcome), so a failed run does not prompt again. */
  private val sessionDone = AtomicBoolean(false)

  /** Makes the "not moved" notice a once-per-session event even when two runs race. */
  private val noticeSent = AtomicBoolean(false)

  /** Whether this session's run failed before recording its decision (see [InstanceSecretStore]). */
  @Volatile var failed: Boolean = false
    private set

  fun ensure() {
    if (sessionDone.get()) return
    try {
      run()
    } catch (e: StorageException) {
      fail(e)
    } catch (e: RuntimeException) {
      fail(e)
    } finally {
      sessionDone.set(true)
    }
  }

  private fun run() {
    val instances = root.node(InstanceSecretStore.INSTANCES_NODE)
    if (instances.get(MARKER_KEY, null) == MARKER_DONE) return
    if (root.nodeExists(LEGACY_NODE)) {
      val legacy = root.node(LEGACY_NODE)
      if (GitLabInstanceKey.of(configuredInstanceUrl()) == GitLabInstanceKey.GITLAB_COM) {
        moveToGitLabCom(legacy, instances)
      } else if (legacy.keys().any { it in SECRET_NAMES } && noticeSent.compareAndSet(false, true)) {
        logger.info("Tokens saved by a previous version were not migrated: the configured instance is not gitlab.com.")
        notify(NOT_MIGRATED_MESSAGE)
      }
    }
    instances.put(MARKER_KEY, MARKER_DONE, false)
    flush()
  }

  private fun moveToGitLabCom(legacy: ISecurePreferences, instances: ISecurePreferences) {
    val target = instances.node(EncodingUtils.encodeSlashes(GitLabInstanceKey.GITLAB_COM.canonical))
    var moved = 0
    for (name in SECRET_NAMES) {
      if (target.get(name, null) != null) continue
      val value = legacy.get(name, null)
      if (value.isNullOrEmpty()) continue
      target.put(name, value, true)
      moved++
    }
    logger.info("Migrated $moved token(s) saved by a previous version to gitlab.com.")
  }

  private fun flush() {
    try {
      root.flush()
    } catch (e: IOException) {
      logger.warn("Failed to save the token migration: ${e::class.java.name}")
    }
  }

  private fun fail(e: Exception) {
    failed = true
    // Type name only: the decision is retried next session.
    logger.warn("Failed to migrate tokens saved by a previous version: ${e::class.java.name}")
  }

  companion object {
    const val LEGACY_NODE = "hosts/gitlab.com"
    const val MARKER_KEY = "legacyMigration"
    const val MARKER_DONE = "1"
    val SECRET_NAMES = listOf("personal_access_token", "oauth_token")

    const val NOT_MIGRATED_MESSAGE =
      "GitLab: Tokens saved by a previous version were not migrated because they cannot be matched to your " +
        "GitLab instance. Enter your personal access token again in Preferences > GitLab."
  }
}
