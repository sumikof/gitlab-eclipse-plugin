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
  /**
   * 設計 §9.1。生成形は [token] を平文で載せる。成分は 12 個あるが生成形の再現は目指さず、
   * 診断に有用なものだけを選ぶ(設計 L-4)。[httpAgentOptions] は入れ子の `toString` に
   * 委譲する(設計 §9.1)— 中身に直接手を伸ばさない。
   */
  override fun toString(): String =
    "GitLabLanguageServerConfigurationParams(baseUrl=$baseUrl, logLevel=$logLevel, " +
      "token=${token?.let { "***" }}, ignoreCertificateErrors=$ignoreCertificateErrors, " +
      "httpAgentOptions=$httpAgentOptions)"

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

  data class HttpAgentOptions(val ca: String?, val cert: String? = null, val certKey: String? = null) {
    /**
     * 設計 §9.1。[ca] は名前パターンに当たらないが CA 証明書のパスまたは内容であり、
     * 設計 §7.2 の明示リストで秘匿と宣言している。
     */
    override fun toString(): String =
      "HttpAgentOptions(ca=${ca?.let { "***" }}, cert=${cert?.let { "***" }}, certKey=${certKey?.let { "***" }})"
  }

  data class Telemetry(
    val enabled: Boolean,
    val trackingUrl: String,

    // This structure is defined in https://gitlab.com/gitlab-org/editor-extensions/gitlab-lsp/-/blob/main/docs/supported_messages.md?ref_type=heads#code-suggestions-telemetry
    val actions: List<Map<String, String>> = TelemetryAction.entries.map { action -> mapOf("action" to action.value) }
  )
}
