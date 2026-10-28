package com.gitlab.eclipse.navigation

import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.util.Locale

/** Why a link was refused (D8a). With [schemeLabel], the only thing about a refused link that is ever logged. */
internal enum class BrowserUrlRefusal { BLANK, UNPARSEABLE, NO_SCHEME, DISALLOWED_SCHEME, NO_HOST, USERINFO }

/** The outcome of [browserUrlVerdict]. */
internal sealed interface BrowserUrlVerdict {
  /**
   * May be opened. [url] is exactly what is handed to the browser: parsed once, never rewritten (`java.net.URL`
   * lower-cases the scheme, nothing else).
   *
   * **Not a data class, on purpose**: `URL.equals` / `hashCode` resolve the host over DNS, and a generated `toString`
   * would print the link. Never compare or key on [url]; use `toExternalForm()` where a string is needed.
   */
  class Allowed(val url: URL) : BrowserUrlVerdict {
    override fun toString(): String = "Allowed(url=<redacted>)"
  }

  /** Refused; both fields are safe to log ([schemeLabel] is a bounded vocabulary, never the raw scheme). */
  data class Refused(val reason: BrowserUrlRefusal, val schemeLabel: String) : BrowserUrlVerdict
}

private val BROWSABLE_SCHEMES = setOf("http", "https")

/** Schemes worth naming in a log. Anything else is logged as `other`: a scheme can be token-shaped (`glpat-…:x`). */
private val LOGGABLE_SCHEMES =
  setOf("http", "https", "javascript", "vbscript", "data", "file", "mailto", "ftp", "about", "blob", "jar", "ssh", "git")

/**
 * The one security decision for every external-browser launch (D8a): an absolute `http` / `https` link (scheme
 * case-insensitive) with a host and no userinfo. Pure — no Display, no log.
 *
 * The input is not trimmed or normalised: surrounding or embedded whitespace and malformed `%` escapes are refused as
 * [BrowserUrlRefusal.UNPARSEABLE]. Ports, queries, fragments, loopback hosts, IPv6 literals and punycode hosts are
 * allowed. A host `java.net.URI` cannot parse as server-based — a Unicode IDN, a host containing `_`, the IPv4
 * shorthand `127.1`, an opaque `https:x` — is refused as [BrowserUrlRefusal.NO_HOST]. Userinfo is refused because it
 * can disguise the real host: `https://gitlab.com@evil.example/` goes to `evil.example`.
 */
internal fun browserUrlVerdict(url: String?): BrowserUrlVerdict {
  if (url.isNullOrBlank()) return refused(BrowserUrlRefusal.BLANK, url)
  val uri = parseOrNull(url) ?: return refused(BrowserUrlRefusal.UNPARSEABLE, url)
  val scheme = uri.scheme ?: return refused(BrowserUrlRefusal.NO_SCHEME, url)
  return when {
    scheme.lowercase(Locale.ROOT) !in BROWSABLE_SCHEMES -> refused(BrowserUrlRefusal.DISALLOWED_SCHEME, url)
    uri.host.isNullOrBlank() -> refused(BrowserUrlRefusal.NO_HOST, url)
    uri.rawUserInfo != null -> refused(BrowserUrlRefusal.USERINFO, url)
    else -> toUrlOrNull(uri)?.let { BrowserUrlVerdict.Allowed(it) } ?: refused(BrowserUrlRefusal.UNPARSEABLE, url)
  }
}

/**
 * A scheme label safe to log: the lower-cased scheme when it is in a fixed vocabulary, else `other`; `none` for null
 * or blank input, `relative` for a reference without a scheme, `unparseable` when it is not a URI at all. The parse
 * failure is discarded, never logged: `URISyntaxException.getMessage` quotes the input in full.
 */
internal fun schemeLabel(url: String?): String {
  if (url.isNullOrBlank()) return "none"
  val uri = parseOrNull(url) ?: return "unparseable"
  val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return "relative"
  return if (scheme in LOGGABLE_SCHEMES) scheme else "other"
}

private fun refused(reason: BrowserUrlRefusal, url: String?) = BrowserUrlVerdict.Refused(reason, schemeLabel(url))

private fun parseOrNull(url: String): URI? =
  try {
    URI(url)
  } catch (ignored: URISyntaxException) {
    null
  }

/** `URI.toURL` does not fail for an absolute http(s) URI with a host in practice; kept total all the same. */
private fun toUrlOrNull(uri: URI): URL? =
  try {
    uri.toURL()
  } catch (ignored: java.net.MalformedURLException) {
    null
  } catch (ignored: IllegalArgumentException) {
    null
  }
