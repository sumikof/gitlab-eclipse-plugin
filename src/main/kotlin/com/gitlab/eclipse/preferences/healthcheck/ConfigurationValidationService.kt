package com.gitlab.eclipse.preferences.healthcheck

import com.gitlab.eclipse.BuildConfig
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationParams
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationParams.*
import com.gitlab.eclipse.lsp.utils.workspaceFolders
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.preferences.PreferenceConstants.LANGUAGE_SERVER_STREAM_CODE_GENERATIONS
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.future.await
import org.eclipse.ui.preferences.ScopedPreferenceStore

class ConfigurationValidationService(
  private val preferenceStore: ScopedPreferenceStore = service(),
  private val languageServerWrapper: GitLabLanguageServerWrapper = service(),
) {
  private val logger by lazy { logger<ConfigurationValidationService>() }

  suspend fun validateConfiguration(request: ConfigurationValidationRequest): Map<String, FeatureStateParams>? {
    val languageServer = languageServerWrapper.languageServer
    if (languageServer == null) {
      logger.error("Could not validate configuration. Language Server is not started.")
      return null
    }

    try {
      val params = GitLabLanguageServerConfigurationParams(
        baseUrl = request.baseUrl,
        token = request.token,
        ignoreCertificateErrors = preferenceStore.getBoolean(PreferenceConstants.IGNORE_CERTIFICATE_ERRORS),
        workspaceFolders = workspaceFolders,
        logLevel = preferenceStore.getString(PreferenceConstants.LANGUAGE_SERVER_LOG_LEVEL),
        telemetry = Telemetry(
          enabled = preferenceStore.getBoolean(PreferenceConstants.TELEMETRY_ENABLED),
          trackingUrl = BuildConfig.SNOWPLOW_COLLECTOR_URL
        ),
        codeCompletion = CodeCompletion(enableSecretRedaction = true),
        featureFlags = FeatureFlags(
          remoteSecurityScans = false,
          streamCodeGenerations = preferenceStore.getBoolean(LANGUAGE_SERVER_STREAM_CODE_GENERATIONS)
        ),
        httpAgentOptions = HttpAgentOptions(
          ca = preferenceStore.getString(PreferenceConstants.CA_CERTIFICATE).takeIf { it.isNotBlank() },
          cert = preferenceStore.getString(PreferenceConstants.CLIENT_CERTIFICATE).takeIf { it.isNotBlank() },
          certKey = preferenceStore.getString(PreferenceConstants.CLIENT_CERTIFICATE_KEY).takeIf { it.isNotBlank() },
        )
      )

      val featuresValidation = languageServer.validateConfiguration(params).await()

      return featuresValidation.associateBy { it.featureId }
    } catch (e: Exception) {
      logger.error("Could not validate configuration.", e)
      return null
    }
  }
}

data class ConfigurationValidationRequest(
  val baseUrl: String,
  val token: String
) {
  /** 設計 §9.1。生成形は [token] を平文で載せる。 */
  override fun toString(): String = "ConfigurationValidationRequest(baseUrl=$baseUrl, token=***)"
}
