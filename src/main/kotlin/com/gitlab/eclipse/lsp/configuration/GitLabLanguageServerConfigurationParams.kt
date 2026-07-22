package com.gitlab.eclipse.lsp.configuration

import com.gitlab.eclipse.telemetry.params.TelemetryAction
import org.eclipse.lsp4j.WorkspaceFolder

data class GitLabLanguageServerConfigurationParams(
  val baseUrl: String? = null,
  val logLevel: String? = null,
  val telemetry: Telemetry? = null,
  val token: String? = null,
  val codeCompletion: CodeCompletion? = null,
  val featureFlags: FeatureFlags? = null,
  val ignoreCertificateErrors: Boolean = false,
  val httpAgentOptions: HttpAgentOptions? = null,
  val workspaceFolders: List<WorkspaceFolder>? = null,
  val duoChat: DuoChat? = null,
  val duo: Duo? = null,
) {
  data class CodeCompletion(
    val enabled: Boolean = true,
    val enableSecretRedaction: Boolean = true,
    val disabledSupportedLanguages: List<String> = emptyList(),
    val additionalLanguages: List<String> = emptyList()
  )

  data class FeatureFlags(val remoteSecurityScans: Boolean, val streamCodeGenerations: Boolean)

  data class DuoChat(val enabled: Boolean)

  // Spelling matters: the language server reads `enabledWithoutGitlabProject` (lowercase "l").
  data class Duo(val enabledWithoutGitlabProject: Boolean)

  data class HttpAgentOptions(val ca: String?, val cert: String? = null, val certKey: String? = null)

  data class Telemetry(
    val enabled: Boolean,
    val trackingUrl: String,

    // This structure is defined in https://gitlab.com/gitlab-org/editor-extensions/gitlab-lsp/-/blob/main/docs/supported_messages.md?ref_type=heads#code-suggestions-telemetry
    val actions: List<Map<String, String>> = TelemetryAction.entries.map { action -> mapOf("action" to action.value) }
  )
}
