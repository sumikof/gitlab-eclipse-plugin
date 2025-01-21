package com.gitlab.eclipse.lsp.configuration

import com.gitlab.eclipse.di.service
import com.gitlab.eclipse.di.Workspace
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationParams.*
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.preferences.PreferenceConstants.GITLAB_INSTANCE_URL
import com.gitlab.eclipse.preferences.PreferenceConstants.IGNORE_CERTIFICATE_ERRORS
import com.gitlab.eclipse.preferences.PreferenceConstants.LANGUAGE_SERVER_LOG_LEVEL
import com.gitlab.eclipse.preferences.PreferenceConstants.LANGUAGE_SERVER_STREAM_CODE_GENERATIONS
import com.gitlab.eclipse.preferences.PreferenceConstants.TELEMETRY_ENABLED
import com.gitlab.eclipse.preferences.storage.SecretStorage
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.ui.preferences.ScopedPreferenceStore

class GitLabLanguageServerConfigurationService(
  private val preferenceStore: ScopedPreferenceStore = Workspace.service(),
  private val languageServerWrapper: GitLabLanguageServerWrapper = Workspace.service(),
  private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
  private val logger by lazy { logger<GitLabLanguageServerConfigurationService>() }

  fun sendConfiguration() {
    val params = GitLabLanguageServerConfigurationParams(
      baseUrl = preferenceStore.getString(GITLAB_INSTANCE_URL),
      codeCompletion = CodeCompletion(enableSecretRedaction = true),
      featureFlags = FeatureFlags(
        remoteSecurityScans = false,
        streamCodeGenerations = preferenceStore.getBoolean(LANGUAGE_SERVER_STREAM_CODE_GENERATIONS)
      ),
      ignoreCertificateErrors = preferenceStore.getBoolean(IGNORE_CERTIFICATE_ERRORS),
      logLevel = preferenceStore.getString(LANGUAGE_SERVER_LOG_LEVEL),
      telemetry = Telemetry(
        preferenceStore.getBoolean(TELEMETRY_ENABLED),
        "https://snowplowprd.trx.gitlab.net"
      ),
      token = SecretStorage("gitlab.com").getSecret("personal_access_token"),
      httpAgentOptions = HttpAgentOptions(
        ca = preferenceStore.getString(PreferenceConstants.CA_CERTIFICATE).takeIf { it.isNotBlank() }
      )
    )

    logger.info("Sending configuration change notification to Language Server.")
    coroutineScope.launch {
      languageServerWrapper.languageServer?.workspaceService?.didChangeConfiguration(
        DidChangeConfigurationParams(params)
      )
    }
  }
}
