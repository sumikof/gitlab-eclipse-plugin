package com.gitlab.eclipse.security.details

import java.util.Collections

/**
 * One file's scan findings, exactly as the language server sent them, plus the context they were
 * recorded under (design §10).
 *
 * [findings] is untyped for the same reason `SecurityScanResponse.results` is: the shape belongs to
 * the language server, and pinning it down here would turn a finding this plugin does not recognise
 * into a parse failure instead of something it can simply skip.
 *
 * **Immutable.** The list is copied on construction and exposed read-only, so a snapshot handed out
 * by `VulnerabilityIntake.read` stays consistent however the caller or the store changes afterwards.
 * That is why this is a plain class rather than a `data class`: a data class cannot copy its
 * constructor argument, and its generated `copy` would let a mutable list back in.
 *
 * [toString] deliberately prints only the finding count and the epoch. Finding bodies and the
 * [contextFingerprint] must never reach a log (design §15).
 *
 * @property timestampMillis when the scan response arrived, or `null` if unknown.
 * @property epoch the connection epoch the findings arrived on.
 * @property contextFingerprint the [ScanContextFingerprint] current when the findings were recorded.
 */
class FileVulnerabilities(
  findings: List<Any?>,
  val timestampMillis: Long?,
  val epoch: Long,
  val contextFingerprint: String,
) {
  /** The findings, in the order the language server sent them. Read-only. */
  val findings: List<Any?> = Collections.unmodifiableList(ArrayList(findings))

  override fun equals(other: Any?): Boolean =
    other is FileVulnerabilities &&
      findings == other.findings &&
      timestampMillis == other.timestampMillis &&
      epoch == other.epoch &&
      contextFingerprint == other.contextFingerprint

  override fun hashCode(): Int {
    var result = findings.hashCode()
    result = HASH_MULTIPLIER * result + timestampMillis.hashCode()
    result = HASH_MULTIPLIER * result + epoch.hashCode()
    result = HASH_MULTIPLIER * result + contextFingerprint.hashCode()
    return result
  }

  override fun toString(): String = "FileVulnerabilities(findings=${findings.size}, epoch=$epoch)"

  private companion object {
    const val HASH_MULTIPLIER = 31
  }
}
