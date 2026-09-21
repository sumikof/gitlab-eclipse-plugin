package com.gitlab.eclipse.diagnostics

import com.gitlab.eclipse.authentication.GitLabTokenProviderManager
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.preferences.PreferenceConstants
import org.eclipse.core.runtime.Platform
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.nio.file.Path

/**
 * Assembles a [DiagnosticsSnapshot] from what the plugin already knows (design §8.2).
 *
 * **Reads nothing over the network** (N1): a user running the diagnostics command is usually
 * running it *because* something is wrong, quite possibly the connection, and a report that hangs
 * on a request is a report they never see. Every value comes from the preference store, a state
 * object, or the OSGi bundle.
 *
 * Every lookup is individually contained. A collector that throws produces no report at all, which
 * is the worst possible outcome for a diagnostics feature; a collector that returns `Not available`
 * for one line still tells the user everything else (design §14).
 *
 * The lambdas are seams with production defaults, the pattern used throughout this codebase
 * (`ClipboardWriter`, `NotificationUtils`): a headless test JVM has neither a running workbench nor
 * a Koin container, and defaulted lambda bodies only run when they are called.
 */
class DiagnosticsSnapshotCollector(
  private val preferences: () -> ScopedPreferenceStore = { service() },
  private val token: () -> String = { service<GitLabTokenProviderManager>().getToken() },
  private val languageServerRunning: () -> Boolean =
    { service<GitLabLanguageServerWrapper>().languageServer != null },
  private val languageServerVersion: () -> String? = LanguageServerVersionState::current,
  private val featureStates: () -> List<FeatureStateSnapshot> = FeatureStateStore::snapshots,
  private val ideVersion: () -> String? = { bundleVersion(PLATFORM_BUNDLE) },
  private val pluginVersion: () -> String? = { bundleVersion(PLUGIN_BUNDLE) },
  private val logDirectory: () -> Path? = { pluginStateDirectory() },
) {

  fun collect(): DiagnosticsSnapshot {
    val store = runCatching(preferences).getOrNull()
    return DiagnosticsSnapshot(
      ideVersion = text(ideVersion),
      pluginVersion = text(pluginVersion),
      languageServerVersion = text(languageServerVersion),
      // U5: never learned without a REST round trip, which §3 rules out. The report says so.
      gitlabInstanceVersion = null,
      instanceUrl = store.string(PreferenceConstants.GITLAB_INSTANCE_URL),
      authenticationType = store.string(PreferenceConstants.AUTHENTICATION_TYPE),
      tokenConfigured = runCatching { token().isNotBlank() }.getOrDefault(false),
      languageServerLogLevel = store.string(PreferenceConstants.LANGUAGE_SERVER_LOG_LEVEL),
      debugLogging = store.flag(PreferenceConstants.DEBUG_LOGGING),
      telemetryEnabled = store.flag(PreferenceConstants.TELEMETRY_ENABLED),
      codeSuggestionsEnabled = store.flag(PreferenceConstants.CODE_SUGGESTIONS_ENABLED),
      duoChatEnabled = store.flag(PreferenceConstants.DUO_CHAT_ENABLED),
      securityScanEnabled = store.flag(PreferenceConstants.SECURITY_SCAN_ENABLED),
      ignoreCertificateErrors = store.flag(PreferenceConstants.IGNORE_CERTIFICATE_ERRORS),
      caCertificateConfigured = store.configured(PreferenceConstants.CA_CERTIFICATE),
      clientCertificateConfigured = store.configured(PreferenceConstants.CLIENT_CERTIFICATE),
      languageServerRunning = runCatching(languageServerRunning).getOrDefault(false),
      languageServerLogPath = text { logDirectory()?.resolve(LANGUAGE_SERVER_LOG)?.toString() },
      featureStates = runCatching(featureStates).getOrDefault(emptyList()),
    )
  }

  private fun text(lookup: () -> String?): String =
    runCatching(lookup).getOrNull()?.takeIf { it.isNotBlank() } ?: NOT_AVAILABLE

  private fun ScopedPreferenceStore?.string(key: String): String =
    runCatching { this?.getString(key) }.getOrNull()?.takeIf { it.isNotBlank() } ?: NOT_AVAILABLE

  private fun ScopedPreferenceStore?.flag(key: String): Boolean =
    runCatching { this?.getBoolean(key) }.getOrNull() ?: false

  /** Whether a certificate is set, never which file it is — the path identifies the machine. */
  private fun ScopedPreferenceStore?.configured(key: String): Boolean =
    runCatching { this?.getString(key)?.isNotBlank() }.getOrNull() ?: false

  companion object {
    const val NOT_AVAILABLE = "Not available"
    const val LANGUAGE_SERVER_LOG = "language_server.log"

    private const val PLATFORM_BUNDLE = "org.eclipse.platform"
    private const val PLUGIN_BUNDLE = "gitlab-eclipse-plugin"

    private fun bundleVersion(symbolicName: String): String? =
      runCatching { Platform.getBundle(symbolicName)?.version?.toString() }.getOrNull()

    /**
     * Where `log4j2.xml` puts `language_server.log`. Read from the same system property
     * `GitLabEclipseStartup` sets for log4j, so the two can never disagree about the location.
     */
    fun pluginStateDirectory(): Path? =
      runCatching { System.getProperty("gitlab.plugin.state.dir")?.let(Path::of) }.getOrNull()
  }
}
