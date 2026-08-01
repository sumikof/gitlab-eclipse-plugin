package com.gitlab.eclipse.ci.actions

import java.net.URI

/**
 * Builds the external artifacts-download URL for a job (design §8.2), or null when [webUrl] is unusable.
 * Validation: non-blank, parseable, http/https scheme, and a non-empty host — so a null/garbage/relative
 * or non-web webUrl never reaches the browser. Mirrors the VSCode literal `${webUrl}/artifacts/download?file_type=archive`.
 */
fun buildArtifactsDownloadUrl(webUrl: String?): String? {
  if (webUrl.isNullOrBlank()) return null
  val uri = try { URI.create(webUrl) } catch (ignored: IllegalArgumentException) { return null }
  val scheme = uri.scheme?.lowercase()
  if (scheme != "http" && scheme != "https") return null
  if (uri.host.isNullOrBlank()) return null
  return "$webUrl/artifacts/download?file_type=archive"
}
