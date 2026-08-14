package com.gitlab.eclipse.publish

/**
 * Puts a git remote URL into the one form the resume path compares on (design §9.6 R2-4).
 *
 * Resuming a failed push must not match on the remote's NAME or on the project's web URL: an SSH
 * remote's user, host and port cannot be recovered from a web URL, and a loose path comparison
 * would happily match a different repository. The record therefore stores the URL that was really
 * added, normalised here, and the comparison is a string equality on that.
 *
 * Only four things are normalised — surrounding whitespace, a trailing slash, a trailing `.git`,
 * and the case of the scheme and host. Deliberately NOT normalised:
 *
 * - **The path's case.** GitLab paths are case sensitive, so `g/Repo` and `g/repo` are different
 *   projects and must not collapse into one.
 * - **The SSH port.** U7 fixed that no SSH port setting exists, so there is no default to fill in;
 *   an explicit port is kept because it distinguishes two remotes on the same host.
 * - **`https` against `ssh`.** The user picks the connection type, and the two forms address the
 *   same project by different means — treating them as equal would resume onto the wrong remote.
 */
object RemoteUrlNormalizer {
  fun normalize(url: String): String {
    val trimmed = url.trim().trimEnd('/').removeSuffix(".git").trimEnd('/')
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd > 0) return normalizeWithScheme(trimmed, schemeEnd)
    return normalizeScpLike(trimmed)
  }

  /** `scheme://[user@]host[:port]/path` — lower-case everything up to the first path separator. */
  private fun normalizeWithScheme(url: String, schemeEnd: Int): String {
    val authorityStart = schemeEnd + SCHEME_SEPARATOR.length
    val pathStart = url.indexOf('/', authorityStart)
    val scheme = url.substring(0, authorityStart).lowercase()
    return if (pathStart < 0) {
      scheme + url.substring(authorityStart).lowercase()
    } else {
      scheme + url.substring(authorityStart, pathStart).lowercase() + url.substring(pathStart)
    }
  }

  /** `[user@]host:path` — lower-case only up to the colon; anything else is left untouched. */
  private fun normalizeScpLike(url: String): String {
    val colon = url.indexOf(':')
    if (colon <= 0) return url
    return url.substring(0, colon).lowercase() + url.substring(colon)
  }

  private const val SCHEME_SEPARATOR = "://"
}
