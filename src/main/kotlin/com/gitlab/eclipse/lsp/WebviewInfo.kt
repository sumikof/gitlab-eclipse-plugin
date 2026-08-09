package com.gitlab.eclipse.lsp

/**
 * One entry of the language server's `webviewMetadata` response.
 *
 * Deserialized by Gson, which bypasses the constructor: [title] and [uris] can be `null` at runtime
 * despite their declared types, and every reader here has to allow for that.
 */
data class WebviewInfo(var id: String, var title: String, var uris: List<String>) {
  /**
   * Design §17, for the same reason as `WebviewEditorKey.toString`: a data class prints every
   * component, and [uris] holds the advertised webview URIs — the same value
   * `WebviewResolution.Resolved.uri` is taken from, one step upstream, and the one §17 keeps out of
   * the log because nothing guarantees it carries no authentication material. Nothing stringifies a
   * `WebviewInfo` today; this replaces the generated form rather than resting on that, because one
   * interpolation anywhere would be enough.
   *
   * Only `toString` changes. Gson reads and writes the fields reflectively, so serialization,
   * `equals` and `hashCode` are untouched.
   */
  override fun toString(): String = "WebviewInfo($id)"
}
