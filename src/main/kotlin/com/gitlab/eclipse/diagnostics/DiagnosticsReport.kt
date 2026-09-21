package com.gitlab.eclipse.diagnostics

import com.gitlab.eclipse.lsp.FeatureStateChangeCheck

/**
 * Renders a [DiagnosticsSnapshot] as the Markdown document the user reads and attaches (design
 * §8.3).
 *
 * The shape follows the reference extension's `generateDiagnosticsMarkdown`: a title, then `##`
 * sections joined by a blank line. The feature-state sections reproduce its `checkEnabledMapper`
 * exactly, **including its inversion** — the server reports a check as `engaged` when that check is
 * what is *blocking* the feature, so an engaged check renders as an unticked box reading `(false)`.
 * Getting that backwards would make a broken install look healthy, which is the one thing this
 * report exists to prevent.
 *
 * Pure: no I/O, no clock, no platform. Everything it needs is in the snapshot.
 */
internal object DiagnosticsReport {

  private const val TITLE = "# GitLab for Eclipse Diagnostics"
  private const val NOT_AVAILABLE = "Not available"

  /** The whole document. Never blank — the fixed sections are always present. */
  fun render(snapshot: DiagnosticsSnapshot): String {
    val sections = buildList {
      add(versions(snapshot))
      add(configuration(snapshot))
      add(languageServer(snapshot))
      // A feature the server has said nothing about yet contributes no section at all, rather than
      // an empty one that reads as "no checks passed".
      snapshot.featureStates.filter { it.checks.isNotEmpty() }.forEach { add(featureState(it)) }
    }
    return (listOf(TITLE) + sections).joinToString("\n\n")
  }

  private fun versions(snapshot: DiagnosticsSnapshot): String = section(
    "Versions",
    listOf(
      "- IDE: Eclipse ${snapshot.ideVersion}",
      "- Plugin: GitLab for Eclipse (${snapshot.pluginVersion})",
      "- Language Server version: ${snapshot.languageServerVersion}",
      "- GitLab instance version: ${snapshot.gitlabInstanceVersion ?: NOT_AVAILABLE}",
    ),
  )

  private fun configuration(snapshot: DiagnosticsSnapshot): String = section(
    "Configuration",
    listOf(
      "- Instance URL: ${snapshot.instanceUrl}",
      "- Authentication type: ${snapshot.authenticationType}",
      "- Token configured: ${yesNo(snapshot.tokenConfigured)}",
      "- Language server log level: ${snapshot.languageServerLogLevel}",
      "- Debug logging: ${enabledDisabled(snapshot.debugLogging)}",
      "- Telemetry: ${enabledDisabled(snapshot.telemetryEnabled)}",
      "- Code Suggestions: ${enabledDisabled(snapshot.codeSuggestionsEnabled)}",
      "- Duo Chat: ${enabledDisabled(snapshot.duoChatEnabled)}",
      "- Security scan: ${enabledDisabled(snapshot.securityScanEnabled)}",
      "- Ignore certificate errors: ${snapshot.ignoreCertificateErrors}",
      "- CA certificate: ${configuredOrNot(snapshot.caCertificateConfigured)}",
      "- Client certificate: ${configuredOrNot(snapshot.clientCertificateConfigured)}",
    ),
  )

  private fun languageServer(snapshot: DiagnosticsSnapshot): String = section(
    "Language Server",
    listOf(
      "- Status: ${if (snapshot.languageServerRunning) "running" else "stopped"}",
      "- Log file: ${snapshot.languageServerLogPath}",
    ),
  )

  /**
   * One feature's section. The heading carries `(On)` / `(Off)`, decided by whether any *rendered*
   * check is engaged — the excluded check is dropped before the verdict, so a file the server has
   * no grammar for cannot make Code Suggestions look switched off.
   */
  private fun featureState(feature: FeatureStateSnapshot): String {
    val rendered = feature.checks.filterNot { it.checkId == FeatureStateLabels.EXCLUDED_CHECK_ID }
    val verdict = if (rendered.any { it.engaged }) "Off" else "On"
    return section("${feature.title} ($verdict)", rendered.map(::checkLine))
  }

  /**
   * `engaged` is inverted on purpose — see the class comment. The details line is shown only for an
   * engaged check, because that is the only case where it explains something the user must act on.
   */
  private fun checkLine(check: FeatureStateChangeCheck): String {
    val box = if (check.engaged) " " else "x"
    val passed = if (check.engaged) "false" else "true"
    val line = "- [$box] ${FeatureStateLabels.labelFor(check.checkId)} ($passed)"
    val details = check.details
    return if (check.engaged && !details.isNullOrBlank()) "$line\n  > $details" else line
  }

  private fun section(title: String, lines: List<String>): String =
    "## $title\n\n${lines.joinToString("\n")}"

  private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

  private fun enabledDisabled(value: Boolean): String = if (value) "enabled" else "disabled"

  private fun configuredOrNot(value: Boolean): String = if (value) "configured" else "not configured"
}
