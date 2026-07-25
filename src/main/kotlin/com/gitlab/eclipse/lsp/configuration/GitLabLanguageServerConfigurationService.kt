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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.ui.preferences.ScopedPreferenceStore

class GitLabLanguageServerConfigurationService(
  private val preferenceStore: ScopedPreferenceStore,
  private val languageServerWrapper: GitLabLanguageServerWrapper,
  private val coroutineScope: CoroutineScope
) {
  private val logger by lazy { logger<GitLabLanguageServerConfigurationService>() }

  // Overload (not a default argument): a default expression reading the wrapper would be
  // evaluated by the Kotlin $default bridge even on MockK mocks, NPE-ing every test that
  // mocks this service and triggers a no-arg send.
  fun sendConfiguration() = sendConfiguration(languageServerWrapper.languageServer)

  fun sendConfiguration(server: GitLabLanguageServer?) {
    val params = GitLabLanguageServerConfigurationParams(
      baseUrl = preferenceStore.getString(GITLAB_INSTANCE_URL),
      codeCompletion = CodeCompletion(
        enabled = preferenceStore.getBoolean(PreferenceConstants.CODE_SUGGESTIONS_ENABLED),
        enableSecretRedaction = true,
        additionalLanguages = service<CodeSuggestionsLanguageService>().getAdditionalLanguages(),
        disabledSupportedLanguages = service<CodeSuggestionsLanguageService>().getDisabledLanguages(),
      ),
      featureFlags = FeatureFlags(
        remoteSecurityScans = false,
        streamCodeGenerations = preferenceStore.getBoolean(LANGUAGE_SERVER_STREAM_CODE_GENERATIONS)
      ),
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

    logger.info("Sending configuration change notification to Language Server.")
    // Send to the server captured at CALL time, never the wrapper's current proxy at
    // coroutine-execution time: a rapid restart may register a new pre-initialize server
    // before this coroutine runs, and the queued work must strand with the old server
    // instead of being redirected at the new one.
    coroutineScope.launch {
      server?.didChangeConfiguration(
        DidChangeConfigurationParams(params)
      )
    }
  }
}
