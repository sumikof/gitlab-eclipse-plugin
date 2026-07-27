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
   * True iff [remoteUri] is an http/https URL whose host equals the host of [instanceUrl]
   * (case-insensitive). Scheme differences between remote and instance are ignored as long as
   * the remote itself is http(s). SSH (`ssh://...`) and scp-like (`git@host:path`) remotes,
   * blank input, and anything unparseable are never a match.
   */
  internal fun hostMatchesInstance(remoteUri: String, instanceUrl: String): Boolean {
    if (remoteUri.isBlank() || instanceUrl.isBlank()) return false
    val remoteHost = httpHostOf(remoteUri) ?: return false
    val instanceHost = parseUri(instanceUrl)?.host ?: return false
    return remoteHost.equals(instanceHost, ignoreCase = true)
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
   * Callback that installs the plugin-owned [sshSessionFactory] on SSH transports only, leaving
   * all other transports untouched. Never mutates JGit's process-wide static SSH factory.
   */
  fun transportConfigCallback(): TransportConfigCallback = TransportConfigCallback { transport ->
    if (transport is SshTransport) {
      transport.sshSessionFactory = sshSessionFactory
    }
  }

  /**
   * Single entry point for fetch/push/etc: applies [credentialsProviderFor] (possibly null) and
   * [transportConfigCallback] to [command] and returns it for chaining.
   */
  fun <C : TransportCommand<C, *>> applyAuth(command: C, remoteUri: String, instanceUrl: String): C {
    command.setCredentialsProvider(credentialsProviderFor(remoteUri, instanceUrl))
    command.setTransportConfigCallback(transportConfigCallback())
    return command
  }

  private fun httpHostOf(uri: String): String? {
    val parsed = parseUri(uri) ?: return null
    val scheme = parsed.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    return parsed.host
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
  }
}
