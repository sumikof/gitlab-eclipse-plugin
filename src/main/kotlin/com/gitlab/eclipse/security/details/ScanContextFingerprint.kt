package com.gitlab.eclipse.security.details

import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationParams
import java.security.MessageDigest

/**
 * An irreversible digest of the settings that decide *where scan findings come from and whether they
 * are allowed* (design §11).
 *
 * Retained findings carry an authorisation boundary (design §14): once the instance, the credential
 * or the scan enablement changes, findings fetched under the previous context must not be shown. The
 * connection epoch cannot see a `didChangeConfiguration`, so this value is compared instead.
 *
 * Exactly four components: `baseUrl`, `token`, `featureFlags.remoteSecurityScans` and
 * `securityScannerOptions.enabled`. Nothing else — log level, telemetry, workspace folders — changes
 * whose findings they are, and keeping those out is what lets a partial send leave the store intact
 * (acceptance A13). Including `token` is provisional (design U7): an OAuth refresh changes it for the
 * same account and so clears findings. That is fail-safe, merely wasteful.
 *
 * **Never log the result**, and never keep the token anywhere but inside this digest: the value is
 * derived from the token, so it is treated as a secret too (design §15).
 */
internal object ScanContextFingerprint {

  /** The lowercase hex SHA-256 of the four components of [params]. */
  fun of(params: GitLabLanguageServerConfigurationParams): String {
    val encoded = StringBuilder()
      .appendComponent(params.baseUrl)
      .appendComponent(params.token)
      .appendComponent(params.featureFlags?.remoteSecurityScans?.toString())
      .appendComponent(params.securityScannerOptions?.enabled?.toString())
    val digest = MessageDigest.getInstance(ALGORITHM).digest(encoded.toString().toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { "%02x".format(it) }
  }

  /**
   * Length-prefixed so the encoding is unambiguous: no value, whatever characters it contains, can
   * shift a boundary into its neighbour. `null` gets its own marker, distinct from `""` (`0:`).
   */
  private fun StringBuilder.appendComponent(value: String?): StringBuilder =
    if (value == null) append(NULL_MARKER) else append(value.length).append(':').append(value)

  private const val ALGORITHM = "SHA-256"
  private const val NULL_MARKER = "-"
}
