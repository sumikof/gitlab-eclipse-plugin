package com.gitlab.eclipse.assignments

/**
 * A remote's host and, when the URL can express one, its port. The scheme is deliberately absent:
 * condition 3 of §11.1 compares an SSH remote against an HTTPS instance URL, where the schemes
 * always differ and comparing them would reject everything.
 */
data class RemoteAuthority(val host: String, val port: Int?)

/**
 * Parses a git remote URL down to its authority, **keeping an explicit SSH port** (design §11.1).
 *
 * The existing `GitLabRemoteParser` must NOT be used here, and this is not a matter of taste. Its
 * own header records that the ported behaviour keeps VSCode's quirks verbatim — including
 * **dropped SSH ports** — and its `remoteMatchesInstance` degrades to a hostname-only comparison
 * whenever the schemes differ, which for SSH remote against HTTPS instance is always. Delegating
 * to it would let **a different service on the same host but another SSH port** pass the check.
 * That parser is a faithful port other features depend on, so it is left exactly as it is.
 *
 * A scp-like remote (`git@host:path`) cannot express a port at all, so [RemoteAuthority.port] is
 * null there. Callers treat a null on either side as "do not compare ports" — U7 fixed that no
 * GitLab SSH port setting exists, so there is no value to compare against and rejecting on that
 * basis would refuse legitimate remotes.
 */
object RemoteAuthorityParser {
  fun parse(remoteUrl: String): RemoteAuthority? {
    val trimmed = remoteUrl.trim()
    if (trimmed.isEmpty()) return null
    val schemeEnd = trimmed.indexOf(SCHEME_SEPARATOR)
    return if (schemeEnd > 0) {
      parseWithScheme(trimmed, schemeEnd)
    } else {
      parseScpLike(trimmed)
    }
  }

  /** `scheme://[user@]host[:port]/path` */
  private fun parseWithScheme(url: String, schemeEnd: Int): RemoteAuthority? {
    val scheme = url.substring(0, schemeEnd).lowercase()
    val authorityStart = schemeEnd + SCHEME_SEPARATOR.length
    val pathStart = url.indexOf('/', authorityStart).takeIf { it >= 0} ?: url.length
    val authority = url.substring(authorityStart, pathStart).substringAfter('@')
    if (authority.isEmpty()) return null
    val colon = authority.lastIndexOf(':')
    if (colon < 0) return RemoteAuthority(authority.lowercase(), defaultPortFor(scheme))
    val port = authority.substring(colon + 1).toIntOrNull() ?: return null
    val host = authority.substring(0, colon)
    if (host.isEmpty()) return null
    return RemoteAuthority(host.lowercase(), port)
  }

  /** `[user@]host:path` — no port is expressible, so none is claimed. */
  private fun parseScpLike(url: String): RemoteAuthority? {
    val colon = url.indexOf(':')
    if (colon <= 0) return null
    val host = url.substring(0, colon).substringAfter('@')
    if (host.isEmpty()) return null
    return RemoteAuthority(host.lowercase(), null)
  }

  private fun defaultPortFor(scheme: String): Int? = when (scheme) {
    "https" -> HTTPS_PORT
    "http" -> HTTP_PORT
    // ssh:// without an explicit port: 22 is the default, but an instance URL never states an SSH
    // port, so claiming it here would only ever produce a mismatch. Left unknown on purpose.
    else -> null
  }

  private const val SCHEME_SEPARATOR = "://"
  private const val HTTPS_PORT = 443
  private const val HTTP_PORT = 80
}
