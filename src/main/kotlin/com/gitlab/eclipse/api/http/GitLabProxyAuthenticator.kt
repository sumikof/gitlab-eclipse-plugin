package com.gitlab.eclipse.api.http

import com.gitlab.eclipse.lsp.proxy.ProxyConfig
import java.net.Authenticator
import java.net.PasswordAuthentication

/**
 * Supplies proxy credentials for 407 challenges only. Answers strictly when the
 * request is a PROXY request whose host:port matches [proxy] — returns null for
 * server (401) challenges so proxy credentials are never sent to the GitLab server.
 */
class GitLabProxyAuthenticator(private val proxy: ProxyConfig) : Authenticator() {
  override fun getPasswordAuthentication(): PasswordAuthentication? {
    if (requestorType != RequestorType.PROXY) return null
    if (!requestingHost.equals(proxy.host, ignoreCase = true) || requestingPort != proxy.port) return null
    val user = proxy.username ?: return null
    val password = proxy.password ?: return null
    return PasswordAuthentication(user, password.toCharArray())
  }
}
