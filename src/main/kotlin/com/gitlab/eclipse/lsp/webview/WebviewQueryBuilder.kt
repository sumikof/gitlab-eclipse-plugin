package com.gitlab.eclipse.lsp.webview

import com.gitlab.eclipse.utils.percentEncodeUnreserved
import java.net.URI
import java.net.URISyntaxException

/** Design §7.3a. */
object WebviewQueryBuilder {
  /**
   * Design §7.3a rules 1-7. Returns null when [baseUri] is not absolute+hierarchical or is
   * unparseable; the caller treats null as a failure. When [params] is empty, [baseUri] is
   * returned verbatim — a bare trailing `?` is not normalized away, unlike the empty-raw-query
   * case below.
   */
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
      "${percentEncodeUnreserved(key)}=${percentEncodeUnreserved(value)}"
    }
    val fragmentSuffix = uri.rawFragment?.let { "#$it" }.orEmpty()

    return base + queryPrefix + appended + fragmentSuffix
  }
}
