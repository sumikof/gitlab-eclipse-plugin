package com.gitlab.eclipse.knowledgegraph

import com.gitlab.eclipse.lsp.webview.DirectWebview

/**
 * Where the Knowledge Graph is being served, once the language server says so (design §8.1).
 *
 * The graph is **not** an LS-hosted webview. The server spawns a separately installed `gkg`
 * binary, reads the port it prints, and tells the client `http://localhost:<port>`. Until that
 * happens — and it never happens if `gkg` is not on the user's PATH — there is nothing to show,
 * which is also exactly how the reference extension behaves.
 *
 * Two writers, both harmless: the `ready` notification (the normal path) and the one-shot `getUrl`
 * request that covers a `ready` fired before the client was listening. They carry the same address
 * from the same process, so last-write-wins needs no coordination.
 */
object KnowledgeGraphState {

  @Volatile
  private var url: String? = null

  /** Records the address. A blank value is ignored rather than clearing a good one. */
  fun record(reported: String?) {
    if (!reported.isNullOrBlank()) url = reported
  }

  /** Forgets the address — used when the connection that reported it goes away. */
  fun clear() {
    url = null
  }

  /**
   * The graph as a directly-addressed webview, or `null` while none is known.
   *
   * Shaped for [com.gitlab.eclipse.lsp.webview.WebviewUriResolver]'s `directUris` seam, which is
   * asked about every webview id; this answers only for [WEBVIEW_ID].
   */
  fun directWebviewFor(id: String): DirectWebview? =
    if (id == WEBVIEW_ID) url?.let { DirectWebview(TITLE, it) } else null

  /**
   * The id the language server uses for the graph plugin, read from the shipped 9.3.0 bundle
   * (`const KNOWLEDGE_GRAPH_WEBVIEW_ID = "knowledge-graph"`). It is a `PluginManager` plugin, not a
   * `webviewPlugins` one, which is why it never appears in `$/gitlab/webview-metadata`.
   */
  const val WEBVIEW_ID: String = "knowledge-graph"

  /** The server's own title for it (`KNOWLEDGE_GRAPH_WEBVIEW_TITLE`). */
  const val TITLE: String = "Knowledge Graph"

  /**
   * Shown instead of the page when nothing has reported an address. The reference extension simply
   * leaves its command disabled, which tells the user nothing; saying why is more useful than
   * hiding the command (design §20 U5).
   */
  const val NOT_RUNNING_MESSAGE: String =
    "The GitLab Knowledge Graph is not running. It is served by the separate `gkg` program: " +
      "install it and make sure it is on your PATH, then restart the GitLab language server."
}
