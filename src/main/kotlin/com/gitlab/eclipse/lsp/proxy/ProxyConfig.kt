package com.gitlab.eclipse.lsp.proxy

/**
 * Structured HTTPS proxy settings for the native REST client egress path.
 * [username]/[password] are only used by PR2 (proxy auth); [toString] masks them.
 */
data class ProxyConfig(
  val host: String,
  val port: Int,
  val bypassHosts: List<String>,
  val username: String?,
  val password: String?,
) {
  override fun toString(): String =
    "ProxyConfig(host=$host, port=$port, bypassHosts=$bypassHosts, " +
      "username=${username?.let { "***" }}, password=${password?.let { "***" }})"
}
