package com.gitlab.eclipse.preferences.storage

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * The GitLab instance a credential belongs to: `scheme://host[:port]path`, normalized so that spellings of the
 * same instance share one key and different instances never do. Tokens are stored and looked up under this key,
 * so a token saved for one instance is never handed to another.
 */
data class GitLabInstanceKey private constructor(val canonical: String) {
  companion object {
    private const val HTTPS_PORT = 443
    private const val HTTP_PORT = 80
    private val PRINTABLE_ASCII = '!'..'~'

    val GITLAB_COM: GitLabInstanceKey = GitLabInstanceKey("https://gitlab.com")

    /** The key of [url], or null when it cannot name an instance (then no token is used for it). */
    fun of(url: String?): GitLabInstanceKey? {
      val parsed = parse(url?.trim().orEmpty()) ?: return null
      // Credentials in the URL are refused, not stripped: `https://gitlab.com@evil.example` goes to evil.example.
      if (parsed.rawUserInfo != null || parsed.rawQuery != null || parsed.rawFragment != null) return null
      val scheme = parsed.scheme?.lowercase(Locale.ROOT)?.takeIf { it == "https" || it == "http" } ?: return null
      val host = parsed.host?.lowercase(Locale.ROOT) ?: return null
      val defaultPort = if (scheme == "https") HTTPS_PORT else HTTP_PORT
      val port = if (parsed.port == -1 || parsed.port == defaultPort) "" else ":${parsed.port}"
      val path = parsed.normalize().rawPath.orEmpty().trimEnd('/')
      val canonical = "$scheme://$host$port$path"
      // Secure storage node names accept printable ASCII only; anything else is refused rather than thrown.
      return if (canonical.all { it in PRINTABLE_ASCII }) GitLabInstanceKey(canonical) else null
    }

    private fun parse(value: String): URI? =
      try {
        URI(value)
      } catch (_: URISyntaxException) {
        null
      }
  }
}
