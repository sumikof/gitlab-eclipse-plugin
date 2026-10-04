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
    private const val NOT_AN_ENDPOINT = "Instance URL does not form a cable endpoint"

    fun of(instanceUrl: String): CableEndpoint {
      val base = parseUri(instanceUrl)
      val (wsScheme, defaultPort) = wsSchemeAndDefaultPort(base.scheme)
      val host = base.host ?: throw IllegalArgumentException("Instance URL has no host")
      val port = base.port.takeIf { it != -1 && it != defaultPort } ?: -1
      val path = base.rawPath.orEmpty().trimEnd('/') + "/-/cable"
      return build(wsScheme, base.scheme.lowercase(), host, port, path)
    }

    /**
     * Re-serialises the parts. Exotic hosts the first parse accepts could still be rejected here
     * (`URISyntaxException` from the constructors, `IllegalArgumentException` from `resolve`); both
     * messages embed the input, so they are replaced by a constant one and only
     * [IllegalArgumentException] ever leaves [of].
     */
    private fun build(wsScheme: String, scheme: String, host: String, port: Int, path: String): CableEndpoint =
      try {
        val uri = URI(wsScheme, null, host, port, null, null, null).resolve(path)
        val origin = URI(scheme, null, host, port, null, null, null).toString()
        CableEndpoint(uri, origin)
      } catch (
        @Suppress("SwallowedException") // Dropped on purpose: its message embeds the instance URL.
        e: URISyntaxException,
      ) {
        throw IllegalArgumentException(NOT_AN_ENDPOINT)
      } catch (
        @Suppress("SwallowedException") // Dropped on purpose: its message embeds the instance URL.
        e: IllegalArgumentException,
      ) {
        throw IllegalArgumentException(NOT_AN_ENDPOINT)
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
