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
  val securityScannerOptions: SecurityScannerOptions? = null,
) {
  data class CodeCompletion(
    val enabled: Boolean = true,
    val enableSecretRedaction: Boolean = true,
    val disabledSupportedLanguages: List<String> = emptyList(),
    val additionalLanguages: List<String> = emptyList()
  )

  data class FeatureFlags(val remoteSecurityScans: Boolean, val streamCodeGenerations: Boolean)

  // The language server reads only `securityScannerOptions.enabled` (design §6.1 P2 step 6).
  data class SecurityScannerOptions(val enabled: Boolean)

  data class DuoChat(val enabled: Boolean)

  // Spelling matters: the language server reads `enabledWithoutGitlabProject` (lowercase "l").
  data class Duo(
    val enabledWithoutGitlabProject: Boolean,
    // The language server's AgenticChatSupportCheck reads `duo.agentPlatform.enabled` and
    // defaults it to FALSE when absent (`get("duo.agentPlatform.enabled") ?? false`), skipping
    // the agentic availability check entirely. It must be sent explicitly for Agentic Chat to
    // ever become available. (`agent-platform-disabled-by-user` treats absent as enabled, so
    // the mismatch is invisible in feature-state checks.)
    val agentPlatform: AgentPlatform? = null,
  )

  data class AgentPlatform(val enabled: Boolean)

  data class HttpAgentOptions(val ca: String?, val cert: String? = null, val certKey: String? = null)

  data class Telemetry(
    val enabled: Boolean,
    val trackingUrl: String,

    // This structure is defined in https://gitlab.com/gitlab-org/editor-extensions/gitlab-lsp/-/blob/main/docs/supported_messages.md?ref_type=heads#code-suggestions-telemetry
    val actions: List<Map<String, String>> = TelemetryAction.entries.map { action -> mapOf("action" to action.value) }
  )
}
