package com.gitlab.eclipse.api.http

import com.gitlab.eclipse.lsp.proxy.ProxyConfig
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/** ProxySelector that routes through [proxy] unless the host matches a bypass rule. */
class BypassAwareProxySelector(private val proxy: ProxyConfig) : ProxySelector() {
  private val proxyEntry = listOf(
    Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(proxy.host, proxy.port))
  )

  override fun select(uri: URI): List<Proxy> {
    val host = uri.host ?: return proxyEntry
    return if (proxy.bypassHosts.any { matches(it, host) }) listOf(Proxy.NO_PROXY) else proxyEntry
  }

  override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) { /* no-op */ }

  private fun matches(pattern: String, host: String): Boolean {
    if (pattern.isBlank()) return false
    if (pattern.startsWith("*.")) {
      val suffix = pattern.substring(1) // ".example.com"
      return host.equals(pattern.substring(2), ignoreCase = true) || host.endsWith(suffix, ignoreCase = true)
    }
    return host.equals(pattern, ignoreCase = true)
  }
}
