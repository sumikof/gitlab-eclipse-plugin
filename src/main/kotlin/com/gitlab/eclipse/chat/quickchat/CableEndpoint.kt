package com.gitlab.eclipse.chat.quickchat

import java.net.URI
import java.net.URISyntaxException

/**
 * Where a GitLab instance serves ActionCable and the `Origin` it accepts (design
 * `quick-chat-streaming` §8.1, K-S1, K-S4). `-/cable` is resolved under the instance path so a
 * sub-path install works (REF `gitlab/api/action_cable.ts:7-12`).
 */
data class CableEndpoint(val uri: URI, val origin: String) {
  companion object {
    private const val HTTPS_PORT = 443
    private const val HTTP_PORT = 80

    fun of(instanceUrl: String): CableEndpoint {
      val base = parseUri(instanceUrl)
      val (wsScheme, defaultPort) = wsSchemeAndDefaultPort(base.scheme)
      val host = base.host ?: throw IllegalArgumentException("Instance URL has no host")
      val port = base.port.takeIf { it != -1 && it != defaultPort } ?: -1
      val path = base.rawPath.orEmpty().trimEnd('/') + "/-/cable"
      val uri = URI(wsScheme, null, host, port, null, null, null).resolve(path)
      val origin = URI(base.scheme.lowercase(), null, host, port, null, null, null).toString()
      return CableEndpoint(uri, origin)
    }

    private fun parseUri(instanceUrl: String): URI =
      try {
        URI(instanceUrl)
      } catch (
        @Suppress("SwallowedException")
        e: URISyntaxException,
      ) {
        // The cause is deliberately dropped: URISyntaxException.message embeds the raw input,
        // which would leak the instance URL through any logger that prints the cause chain.
        throw IllegalArgumentException("Instance URL is not a URI")
      }

    private fun wsSchemeAndDefaultPort(scheme: String?): Pair<String, Int> =
      when (scheme?.lowercase()) {
        "https" -> "wss" to HTTPS_PORT
        "http" -> "ws" to HTTP_PORT
        else -> throw IllegalArgumentException("Instance URL must be http or https")
      }
  }
}
