package com.gitlab.eclipse.lsp.webview

/**
 * A webview whose address the client already knows, rather than one the language server advertises
 * through `$/gitlab/webview-metadata` (design §16).
 *
 * Exists for exactly one case today: the Knowledge Graph. Its language-server plugin is registered
 * on the `PluginManager` rather than in `webviewPlugins`, so it is never listed in the metadata
 * response; instead the server hands the client an `http://localhost:<port>` address served by the
 * separately installed `gkg` process. Resolving it through metadata would always answer
 * `NotAdvertised`.
 */
data class DirectWebview(val title: String, val uri: String)
