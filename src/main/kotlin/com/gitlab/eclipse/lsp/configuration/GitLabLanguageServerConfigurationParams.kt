package com.gitlab.eclipse.lsp.configuration

data class GitLabLanguageServerConfigurationParams(
  val baseUrl: String? = null,
  val logLevel: String? = null,
  val telemetry: Telemetry? = null,
  val token: String? = null,
  val codeCompletion: CodeCompletion? = null,
  val featureFlags: FeatureFlags? = null,
  val ignoreCertificateErrors: Boolean = false,
  val httpAgentOptions: HttpAgentOptions? = null
) {
  data class CodeCompletion(
    val enableSecretRedaction: Boolean = true,
    val disabledSupportedLanguages: List<String> = emptyList(),
    val additionalLanguages: List<String> = emptyList()
  )

  data class FeatureFlags(val remoteSecurityScans: Boolean, val streamCodeGenerations: Boolean)

  data class HttpAgentOptions(val ca: String?, val cert: String? = null, val certKey: String? = null)

  data class Telemetry(val enabled: Boolean, val trackingUrl: String)
}
