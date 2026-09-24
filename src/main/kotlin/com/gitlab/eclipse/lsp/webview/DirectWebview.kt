package com.gitlab.eclipse.lsp.webview

/**
 * A webview whose address the client already knows, rather than one the language server advertises
 * through `$/gitlab/webview-metadata`.
 *
 * Exists for exactly one case today: the Knowledge Graph. Its language-server plugin is registered
 * on the `PluginManager` rather than in `webviewPlugins`, so it is never listed in the metadata
 * response; instead the server hands the client an `http://localhost:<port>` address served by the
 * separately installed `gkg` process. Resolving it through metadata would always answer
 * `NotAdvertised`.
 */
data class DirectWebview(val title: String, val uri: String) {
  /**
   * For the same reason as `WebviewResolution.Resolved.toString`: a data class prints every
   * component, and [uri] is an address §15 keeps out of the log. Only the title survives.
   */
  override fun toString(): String = "DirectWebview($title)"
}
