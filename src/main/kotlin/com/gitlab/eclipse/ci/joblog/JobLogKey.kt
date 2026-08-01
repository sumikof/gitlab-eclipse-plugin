package com.gitlab.eclipse.ci.joblog

import com.gitlab.eclipse.ci.actions.normalizeInstanceUrl
import java.security.MessageDigest

private const val CONN_HASH_LENGTH = 16

/**
 * Connection-namespaced identity of a job-log editor (AC-8 / NFR-5).
 *
 * [connHash] is a non-reversible hash of the normalized instance URL plus the auth fingerprint,
 * so the editor identity never embeds the raw URL or credential material. A different instance
 * OR a different account yields a different key, preventing cross-account log mixing.
 */
data class JobLogKey(val connHash: String, val projectId: Long, val jobId: Long) {
  companion object {
    fun of(instanceUrl: String, authFingerprint: String, projectId: Long, jobId: Long): JobLogKey =
      JobLogKey(
        connHash = sha256Hex(normalizeInstanceUrl(instanceUrl) + "\n" + authFingerprint)
          .take(CONN_HASH_LENGTH),
        projectId = projectId,
        jobId = jobId,
      )
  }
}

private fun sha256Hex(input: String): String =
  MessageDigest.getInstance("SHA-256")
    .digest(input.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
