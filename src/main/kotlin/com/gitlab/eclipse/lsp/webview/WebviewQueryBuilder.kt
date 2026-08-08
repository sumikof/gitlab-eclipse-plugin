package com.gitlab.eclipse.lsp.webview

import java.net.URI
import java.net.URISyntaxException

/** Design §7.3a. */
object WebviewQueryBuilder {
  private const val BYTE_MASK = 0xFF
  private const val HEX_RADIX = 16

  /** Design §7.3a rules 1-8. */
  fun append(baseUri: String, params: Map<String, String>): String? {
    val uri = try {
      URI(baseUri)
    } catch (_: URISyntaxException) {
      return null
    }
    if (uri.isOpaque || !uri.isAbsolute) return null
    if (params.isEmpty()) return baseUri

    val cutIndex = baseUri.indexOfFirst { it == '?' || it == '#' }
    val base = if (cutIndex == -1) baseUri else baseUri.substring(0, cutIndex)

    val rawQuery = uri.rawQuery
    val queryPrefix = if (rawQuery.isNullOrEmpty()) "?" else "?$rawQuery&"
    val appended = params.entries.joinToString("&") { (key, value) ->
      "${encodeComponent(key)}=${encodeComponent(value)}"
    }
    val fragmentSuffix = uri.rawFragment?.let { "#$it" }.orEmpty()

    return base + queryPrefix + appended + fragmentSuffix
  }

  private fun isUnreserved(byte: Int): Boolean =
    byte in 'A'.code..'Z'.code || byte in 'a'.code..'z'.code || byte in '0'.code..'9'.code ||
      byte == '-'.code || byte == '_'.code || byte == '.'.code || byte == '~'.code

  private fun encodeComponent(value: String): String = buildString {
    for (raw in value.toByteArray(Charsets.UTF_8)) {
      val byte = raw.toInt() and BYTE_MASK
      if (isUnreserved(byte)) {
        append(byte.toChar())
      } else {
        append('%')
        append(byte.toString(HEX_RADIX).uppercase().padStart(2, '0'))
      }
    }
  }
}
