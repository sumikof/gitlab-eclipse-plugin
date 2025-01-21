package com.gitlab.eclipse.lsp.configuration

import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationParams.*
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.preferences.PreferenceConstants.GITLAB_INSTANCE_URL
import com.gitlab.eclipse.preferences.PreferenceConstants.IGNORE_CERTIFICATE_ERRORS
import com.gitlab.eclipse.preferences.PreferenceConstants.LANGUAGE_SERVER_LOG_LEVEL
import com.gitlab.eclipse.preferences.PreferenceConstants.LANGUAGE_SERVER_STREAM_CODE_GENERATIONS
import com.gitlab.eclipse.preferences.PreferenceConstants.TELEMETRY_ENABLED
import com.gitlab.eclipse.preferences.storage.SecretStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.eclipse.core.runtime.preferences.InstanceScope
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.ui.preferences.ScopedPreferenceStore
import org.osgi.framework.FrameworkUtil

class GitLabLanguageServerConfigurationService(
  private val languageServerWrapper: GitLabLanguageServerWrapper = GitLabLanguageServerWrapper(),
  private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
  fun sendConfiguration() {
    val preferenceStore = ScopedPreferenceStore(
      InstanceScope.INSTANCE,
      FrameworkUtil.getBundle(GitLabLanguageServerConfigurationService::class.java).bundleId.toString()
    )

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

    coroutineScope.launch {
      languageServerWrapper.languageServer?.workspaceService?.didChangeConfiguration(
        DidChangeConfigurationParams(params)
      )
    }
  }
}
