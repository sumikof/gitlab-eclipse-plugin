package com.gitlab.eclipse.security.details

/**
 * A read-only map that behaves as [delegate] in every respect except [toString], which names only
 * [label] and the keys (PR #89 review, design §15).
 *
 * The webview payload and projection carry finding text and a file name, and a plain map prints all of
 * it recursively the moment it is interpolated into a log line or an exception message. Staying a
 * `Map` keeps what is sent unchanged: Gson and lsp4j serialise any `Map` as a JSON object from its
 * entries, and equality with an ordinary map with the same entries holds both ways.
 *
 * Only the map's own `toString` is redacted: the `keys` / `values` / `entries` views are the delegate's
 * and print as usual, so log the map, never a view of it.
 */
internal class RedactedMap(
  private val label: String,
  private val delegate: Map<String, Any?>,
) : Map<String, Any?> by delegate {

  override fun equals(other: Any?): Boolean = delegate == other

  override fun hashCode(): Int = delegate.hashCode()

  override fun toString(): String = "$label(keys=${delegate.keys})"
}
