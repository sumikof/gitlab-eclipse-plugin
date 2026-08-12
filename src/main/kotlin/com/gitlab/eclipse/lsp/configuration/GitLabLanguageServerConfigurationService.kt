package com.gitlab.eclipse.lsp.configuration

import com.gitlab.eclipse.BuildConfig
import com.gitlab.eclipse.authentication.GitLabTokenProviderManager
import com.gitlab.eclipse.codesuggestions.languages.CodeSuggestionsLanguageService
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationParams.*
import com.gitlab.eclipse.lsp.utils.workspaceFolders
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.preferences.PreferenceConstants.GITLAB_INSTANCE_URL
import com.gitlab.eclipse.preferences.PreferenceConstants.IGNORE_CERTIFICATE_ERRORS
import com.gitlab.eclipse.preferences.PreferenceConstants.LANGUAGE_SERVER_LOG_LEVEL
import com.gitlab.eclipse.preferences.PreferenceConstants.LANGUAGE_SERVER_STREAM_CODE_GENERATIONS
import com.gitlab.eclipse.preferences.PreferenceConstants.TELEMETRY_ENABLED
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.ui.preferences.ScopedPreferenceStore

class GitLabLanguageServerConfigurationService(
  private val preferenceStore: ScopedPreferenceStore,
  private val languageServerWrapper: GitLabLanguageServerWrapper,
  private val coroutineScope: CoroutineScope,
  private val outboundLock: Mutex,
) {
  private val logger by lazy { logger<GitLabLanguageServerConfigurationService>() }

  // Overload (not a default argument): a default expression reading the wrapper would be
  // evaluated by the Kotlin $default bridge even on MockK mocks, NPE-ing every test that
  // mocks this service and triggers a no-arg send.
  fun sendConfiguration() = sendConfiguration(languageServerWrapper.languageServer)

  fun sendConfiguration(server: GitLabLanguageServer?) {
    logger.info("Sending configuration change notification to Language Server.")
    // Send to the server captured at CALL time, never the wrapper's current proxy at
    // coroutine-execution time: a rapid restart may register a new pre-initialize server
    // before this coroutine runs, and the queued work must strand with the old server
    // instead of being redirected at the new one.
    coroutineScope.launch {
      try {
        outboundLock.withLock {
          // Read the configuration HERE, under the lock, rather than at call time. The `Mutex`
          // grants exclusion but never arrival order, and the dispatcher decides which queued
          // send reaches `lock()` first, so a snapshot taken at call time can be transmitted
          // after a newer one. The configuration is sent in full, so that older snapshot then
          // stands as the server's whole state until something sends again — a toggle silently
          // reverts. Reading under the lock makes the snapshot and the send one indivisible
          // step, which is all the ordering this needs: every caller writes the preference
          // store BEFORE calling, so whoever wins the lock reads the newest values and the
          // last send to run is the newest one (issue #16). SecurityScanLauncher already
          // builds its params inside its own lock region for the same reason.
          server?.didChangeConfiguration(
            DidChangeConfigurationParams(buildParams())
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // `buildParams()` runs on the coroutine now, so failures that used to surface
        // synchronously at the caller land here instead: it reads
        // `ResourcesPlugin.getWorkspace()` (IllegalStateException once the resources bundle
        // winds down) and refreshes the OAuth token over the network. This is the SHARED
        // plain-Job scope — an escape would cancel it and every other coroutine on it (same
        // shape as ProjectOpenLanguageServerListener). Type only: the params carry a token
        // and certificate paths.
        logger.error("Configuration change notification failed: ${e.javaClass.name}")
      }
    }
  }

  /**
   * Snapshot of the whole configuration, read from the preference store when it is called.
   *
   * Call it inside the outbound `Mutex` region that sends the result. Building it earlier and
   * carrying the value into the lock reintroduces issue #16: a `Mutex` grants exclusion, never
   * arrival order, so the older of two snapshots can be the one transmitted last.
   *
   * Exposed separately from [sendConfiguration] for callers that must send the configuration and
   * something that depends on it back to back, inside one region of that `Mutex`.
   * [sendConfiguration] cannot serve them: it queues its own coroutine, so a caller already holding
   * the `Mutex` would deadlock, and one that is not would have no way to keep the two notifications
   * in order.
   */
  internal fun buildParams(): GitLabLanguageServerConfigurationParams {
    val securityScanEnabled = preferenceStore.getBoolean(PreferenceConstants.SECURITY_SCAN_ENABLED)
    return GitLabLanguageServerConfigurationParams(
      baseUrl = preferenceStore.getString(GITLAB_INSTANCE_URL),
      codeCompletion = CodeCompletion(
        enabled = preferenceStore.getBoolean(PreferenceConstants.CODE_SUGGESTIONS_ENABLED),
        enableSecretRedaction = true,
        additionalLanguages = service<CodeSuggestionsLanguageService>().getAdditionalLanguages(),
        disabledSupportedLanguages = service<CodeSuggestionsLanguageService>().getDisabledLanguages(),
      ),
      featureFlags = FeatureFlags(
        remoteSecurityScans = securityScanEnabled,
        streamCodeGenerations = preferenceStore.getBoolean(LANGUAGE_SERVER_STREAM_CODE_GENERATIONS)
      ),
      securityScannerOptions = SecurityScannerOptions(enabled = securityScanEnabled),
      ignoreCertificateErrors = preferenceStore.getBoolean(IGNORE_CERTIFICATE_ERRORS),
      logLevel = preferenceStore.getString(LANGUAGE_SERVER_LOG_LEVEL),
      telemetry = Telemetry(
        preferenceStore.getBoolean(TELEMETRY_ENABLED),
        BuildConfig.SNOWPLOW_COLLECTOR_URL
      ),
      token = service<GitLabTokenProviderManager>().getToken(),
      httpAgentOptions = HttpAgentOptions(
        ca = preferenceStore.getString(PreferenceConstants.CA_CERTIFICATE).takeIf { it.isNotBlank() },
        cert = preferenceStore.getString(PreferenceConstants.CLIENT_CERTIFICATE).takeIf { it.isNotBlank() },
        certKey = preferenceStore.getString(PreferenceConstants.CLIENT_CERTIFICATE_KEY).takeIf { it.isNotBlank() },
      ),
      workspaceFolders = workspaceFolders,
      duoChat = DuoChat(enabled = preferenceStore.getBoolean(PreferenceConstants.DUO_CHAT_ENABLED)),
      duo = Duo(
        enabledWithoutGitlabProject = preferenceStore.getBoolean(
          PreferenceConstants.DUO_ENABLED_WITHOUT_GITLAB_PROJECT
        ),
        // Required for Agentic Chat: the LS agentic support check treats an absent
        // `duo.agentPlatform.enabled` as false and never evaluates availability.
        agentPlatform = GitLabLanguageServerConfigurationParams.AgentPlatform(enabled = true),
      )
    )
  }
}
