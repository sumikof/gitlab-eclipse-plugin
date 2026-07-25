package com.gitlab.eclipse.navigation

import java.net.URI

/** Parsed GitLab remote. Field semantics mirror WHATWG URL used by VSCode git_remote_parser.ts. */
data class GitLabRemote(
  val host: String,
  val hostname: String,
  val protocol: String,
  val namespace: String,
  val projectPath: String,
  val namespaceWithPath: String,
)

/**
 * Kotlin port of gitlab-workflow v6.85.3 `src/desktop/git/git_remote_parser.ts`
 * (`normalizeSshRemote` :49-91, `parseGitLabRemote` :93-114, `parseProject` :122-142).
 * Behavior (incl. quirks: dropped SSH ports, unescaped `.git` dot, scheme replacement) is preserved verbatim.
 */
object GitLabRemoteParser {
  private const val HTTPS_DEFAULT_PORT = 443
  private const val HTTP_DEFAULT_PORT = 80

  fun parseGitLabRemote(remote: String, instanceUrl: String? = null): GitLabRemote? {
    val uri = tryParseUri(normalizeSshRemote(remote)) ?: return null
    val host = whatwgHost(uri) ?: return null
    val hostname = uri.host?.lowercase() ?: return null
    val protocol = (uri.scheme ?: return null).lowercase() + ":"
    val pathname = uri.rawPath ?: return null
    if (pathname.isEmpty()) return null

    val instancePath = if (instanceUrl != null) escapeForRegExp(getInstancePath(instanceUrl)) else ""
    // Faithful port of :105 — note (?:.git)? keeps the unescaped dot on purpose.
    val pathRegex = Regex("(?:$instancePath)?/:?(.+)/([^/]+?)(?:.git)?/?$")
    val m = pathRegex.find(pathname) ?: return null
    val namespace = m.groupValues[1]
    val projectPath = m.groupValues[2]
    return GitLabRemote(host, hostname, protocol, namespace, projectPath, "$namespace/$projectPath")
  }

  /** Port of parseProject :130-138 host/protocol matching (used to validate/select the remote). */
  fun remoteMatchesInstance(remote: GitLabRemote, instanceUrl: String): Boolean {
    val url = tryParseUri(instanceUrl) ?: return false
    val instProtocol = (url.scheme ?: return false).lowercase() + ":"
    val instHost = whatwgHost(url) ?: return false
    val instHostname = url.host?.lowercase() ?: return false
    if (instProtocol == remote.protocol && instHost != remote.host) return false
    if (instProtocol != remote.protocol && instHostname != remote.hostname) return false
    return true
  }

  // --- helpers (ported) ---

  private fun tryParseUri(s: String): URI? = try {
    val u = URI(s)
    if (u.host == null || u.scheme == null) null else u
  } catch (_: Exception) {
    null
  }

  /** WHATWG `host`: hostname + port, dropping the scheme-default port. */
  private fun whatwgHost(uri: URI): String? {
    val hostname = uri.host?.lowercase() ?: return null
    val port = uri.port
    if (port == -1) return hostname
    val scheme = uri.scheme?.lowercase()
    val isDefault = (scheme == "https" && port == HTTPS_DEFAULT_PORT) || (scheme == "http" && port == HTTP_DEFAULT_PORT)
    return if (isDefault) hostname else "$hostname:$port"
  }

  /** :41-45 getInstancePath — pathname with one trailing slash removed, "" if unparseable. */
  private fun getInstancePath(instanceUrl: String): String {
    val path = tryParseUri(instanceUrl)?.rawPath ?: return ""
    return path.replace(Regex("/$"), "")
  }

  /** :47 escapeForRegExp — escapes `- [ ] / { } ( ) * + ? . \ ^ $ |`. */
  private fun escapeForRegExp(s: String): String =
    s.replace(Regex("[-\\[\\]/{}()*+?.\\\\^$|]"), "\\\\$0")

  /** :49-91 normalizeSshRemote — five branches, first match wins; then fallback. */
  private fun normalizeSshRemote(remote: String): String {
    Regex("^\\[([a-zA-Z0-9_-]+@.*?):\\d+\\](.*)$").find(remote)?.let {
      return "ssh://${it.groupValues[1]}/${it.groupValues[2]}"
    }
    Regex("^ssh://([a-zA-Z0-9_-]+@.*?):\\d+(.*)$").find(remote)?.let {
      return "ssh://${it.groupValues[1]}${it.groupValues[2]}"
    }
    Regex("([a-zA-Z0-9_-]+@.*?):/(.*)").find(remote)?.let {
      return "ssh://${it.groupValues[1]}/${it.groupValues[2]}"
    }
    Regex("([a-zA-Z0-9_-]+@.*?):(.*)").find(remote)?.let {
      return "ssh://${it.groupValues[1]}/${it.groupValues[2]}"
    }
    if (Regex("^[a-zA-Z0-9_-]+@").find(remote) != null) return "ssh://$remote"
    return remote
  }
}
