package com.gitlab.eclipse.mergerequests

import com.gitlab.eclipse.authentication.GitLabTokenProviderManager
import com.gitlab.eclipse.inject.service
import org.eclipse.jgit.api.TransportCommand
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.transport.sshd.SshdSessionFactory
import java.net.URI
import java.net.URISyntaxException

/**
 * Configures JGit transport authentication for MR git operations (Phase 3 §7.1).
 *
 * HTTPS remotes pointing at the configured GitLab instance get an `oauth2:<token>` credentials
 * provider; every other remote (SSH, scp-like, other hosts) gets none, so JGit falls back to its
 * defaults. SSH transports get a PLUGIN-OWNED [SshdSessionFactory] set per-transport via a
 * [TransportConfigCallback] — the process-wide static `SshSessionFactory.setInstance` is
 * deliberately never called, because that would hijack SSH for EGit and every other JGit user in
 * the same JVM. The token is never logged and never embedded in a URL.
 */
class GitAuthConfigurer(private val tokenManager: GitLabTokenProviderManager = service()) {

  /**
   * Plugin-owned SSH session factory. The default constructor picks up the user's `~/.ssh`
   * config, keys, known_hosts and a running ssh-agent. Created lazily so purely-HTTPS usage
   * never touches the SSH stack.
   */
  private val sshSessionFactory: SshdSessionFactory by lazy { SshdSessionFactory() }

  /**
   * True iff [remoteUri] and [instanceUrl] are http(s) URLs sharing the same secure origin —
   * identical scheme, host (case-insensitive), and effective port (the scheme default, 443/80,
   * when none is given). Requiring a scheme match means the `oauth2:<token>` credential is never
   * attached to an `http://` remote when the instance is `https://` (no plaintext token leak on
   * an HTTP downgrade), and requiring a port match keeps a different port on the same host in a
   * separate credential scope. SSH (`ssh://...`) and scp-like (`git@host:path`) remotes, blank
   * input, and anything unparseable are never a match.
   */
  internal fun hostMatchesInstance(remoteUri: String, instanceUrl: String): Boolean {
    if (remoteUri.isBlank() || instanceUrl.isBlank()) return false
    val remoteOrigin = httpOriginOf(remoteUri) ?: return false
    val instanceOrigin = httpOriginOf(instanceUrl) ?: return false
    return remoteOrigin == instanceOrigin
  }

  /**
   * Returns an `oauth2:<token>` [UsernamePasswordCredentialsProvider] when [remoteUri] is an
   * http(s) remote on the GitLab instance host and a token is available; null otherwise (JGit
   * then uses its default credentials resolution). The token never goes into a URL.
   */
  fun credentialsProviderFor(remoteUri: String, instanceUrl: String): CredentialsProvider? {
    if (!hostMatchesInstance(remoteUri, instanceUrl)) return null
    val token = tokenManager.getToken()
    if (token.isEmpty()) return null
    return UsernamePasswordCredentialsProvider(OAUTH2_USERNAME, token)
  }

  /**
   * Per-transport configuration callback. JGit invokes it once for EACH transport a command
   * opens, so credentials are scoped to that transport's own URI rather than applied blanket to
   * the whole command:
   *  - SSH transports get the plugin-owned [sshSessionFactory] (never JGit's process-wide static).
   *  - A transport whose URI is the GitLab instance over HTTPS gets the `oauth2:<token>` provider.
   *  - Every other transport is left untouched, so JGit keeps its default credentials provider —
   *    the token is never handed to another host (even a second `pushurl`), and destinations with
   *    the user's own stored credentials still authenticate.
   */
  fun transportConfigCallback(instanceUrl: String): TransportConfigCallback = TransportConfigCallback { transport ->
    if (transport is SshTransport) {
      transport.sshSessionFactory = sshSessionFactory
    }
    credentialsProviderFor(transport.uri.toString(), instanceUrl)?.let { provider ->
      transport.setCredentialsProvider(provider)
    }
  }

  /**
   * Single entry point for fetch/push/etc: wires the per-transport [transportConfigCallback] onto
   * [command] and returns it for chaining. Deliberately does NOT set a command-level credentials
   * provider — a single provider would be applied to every transport the command opens (leaking
   * the token to other hosts on multi-URL remotes) and would replace JGit's default provider with
   * null on non-matching destinations. Credentials are scoped per transport in the callback.
   */
  fun <C : TransportCommand<C, *>> applyAuth(command: C, instanceUrl: String): C {
    command.setTransportConfigCallback(transportConfigCallback(instanceUrl))
    return command
  }

  /** Scheme/host/effective-port of an http(s) URI; null for non-http(s), host-less, or unparseable input. */
  private data class HttpOrigin(val scheme: String, val host: String, val port: Int)

  private fun httpOriginOf(uri: String): HttpOrigin? {
    val parsed = parseUri(uri) ?: return null
    val scheme = parsed.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    val host = parsed.host?.lowercase() ?: return null
    val port = if (parsed.port != -1) parsed.port else if (scheme == "https") HTTPS_PORT else HTTP_PORT
    return HttpOrigin(scheme, host, port)
  }

  private fun parseUri(value: String): URI? =
    try {
      URI(value)
    } catch (_: URISyntaxException) {
      null
    }

  private companion object {
    /** GitLab's fixed username for token-over-HTTPS git auth (`oauth2:<token>`). */
    const val OAUTH2_USERNAME = "oauth2"
    const val HTTPS_PORT = 443
    const val HTTP_PORT = 80
  }
}
