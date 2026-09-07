package com.gitlab.eclipse.lsp

import java.net.URI

/**
 * True when [url] may be opened in the user's external browser.
 *
 * The URI arrives from the language server, so it is untrusted: only absolute http/https URLs
 * with a host are allowed through.
 */
fun isBrowsableExternalUrl(url: String?): Boolean {
  if (url.isNullOrBlank()) return false
  val uri = try { URI.create(url) } catch (ignored: IllegalArgumentException) { return false }
  val scheme = uri.scheme?.lowercase()
  if (scheme != "http" && scheme != "https") return false
  if (uri.host.isNullOrBlank()) return false
  return true
}
