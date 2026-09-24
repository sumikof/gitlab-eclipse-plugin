package com.gitlab.eclipse.lsp.messages

/**
 * Parameters of the language server's `$/gitlab/openUrl` notification: `{ url }`, where `url` is the
 * `href` of a link clicked in a webview.
 *
 * [url] is nullable on purpose, as in [CopyTextParams]: Gson builds this without the Kotlin
 * constructor, so a missing or null field arrives as `null` whatever the declared type says. Whatever
 * arrives is untrusted and goes through `VulnerabilityLinkPolicy` before anything is opened.
 */
data class OpenUrlParams(val url: String?)
