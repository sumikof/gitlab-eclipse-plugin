package com.gitlab.eclipse.diagnostics

import com.gitlab.eclipse.lsp.FeatureStateChangeCheck

/**
 * Everything the diagnostics report says about this installation, as of one moment (design §12).
 *
 * **No secret ever enters this type.** Where the reference extension would print a value, this
 * carries a yes/no: [tokenConfigured] instead of the token, [caCertificateConfigured] and
 * [clientCertificateConfigured] instead of the certificate paths. That is the first of the two
 * defences in design §10 — [DiagnosticsSanitizer] is the second, for the text the plugin does not
 * author (log lines and stack traces). Keeping secrets out here is what makes the first defence
 * real rather than a promise about formatting.
 *
 * It follows that this class must never gain a field holding a token, a password or a certificate
 * path. `SecretRedactionConventionTest` enforces the toString side of that rule for types that do
 * hold one; this type stays out of its scope by construction.
 *
 * A plain snapshot: it is not guaranteed to be internally consistent across its fields, because it
 * is assembled by reading several independent services in turn (design §13). For a diagnostics
 * report that is the right trade — a torn read shows the user two adjacent truths, which is still
 * more than they had.
 */
data class DiagnosticsSnapshot(
  val ideVersion: String,
  val pluginVersion: String,
  val languageServerVersion: String,
  /** `null` when it was never learned; the report never makes a request to find out (design §3). */
  val gitlabInstanceVersion: String?,
  val instanceUrl: String,
  val authenticationType: String,
  val tokenConfigured: Boolean,
  val languageServerLogLevel: String,
  val debugLogging: Boolean,
  val telemetryEnabled: Boolean,
  val codeSuggestionsEnabled: Boolean,
  val duoChatEnabled: Boolean,
  val securityScanEnabled: Boolean,
  val ignoreCertificateErrors: Boolean,
  val caCertificateConfigured: Boolean,
  val clientCertificateConfigured: Boolean,
  val languageServerRunning: Boolean,
  val languageServerLogPath: String,
  val featureStates: List<FeatureStateSnapshot>,
)

/**
 * One feature's checks as the language server last reported them, under the title the report shows.
 *
 * [checks] is the server's own `allChecks` list, reused verbatim from
 * [com.gitlab.eclipse.lsp.FeatureStateChange] rather than re-modelled: the report renders exactly
 * what arrived, and a server that adds a check needs no change here.
 */
data class FeatureStateSnapshot(
  val title: String,
  val checks: List<FeatureStateChangeCheck>,
)
