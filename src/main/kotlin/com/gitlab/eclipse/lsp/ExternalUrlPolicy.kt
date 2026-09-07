package com.gitlab.eclipse.lsp

import java.net.URI

/**
 * True when [url] may be opened in the user's external browser.
 *
 * The URI arrives from the language server, so it is untrusted: only absolute http/https URLs
 * with a host and no userinfo are allowed through. Userinfo (`user@host`) is rejected because it
 * lets an attacker disguise the real target host as credentials in front of a host they control,
 * e.g. `https://gitlab.com@evil.example/x` actually points at `evil.example`.
 */
fun isBrowsableExternalUrl(url: String?): Boolean {
  if (url.isNullOrBlank()) return false
  val uri = try { URI.create(url) } catch (ignored: IllegalArgumentException) { return false }
  val scheme = uri.scheme?.lowercase()
  if (scheme != "http" && scheme != "https") return false
  if (uri.host.isNullOrBlank()) return false
  if (uri.rawUserInfo != null) return false
  return true
}
