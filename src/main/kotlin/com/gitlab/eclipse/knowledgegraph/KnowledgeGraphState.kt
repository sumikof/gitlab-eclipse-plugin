package com.gitlab.eclipse.knowledgegraph

import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.lsp.webview.DirectWebview

/**
 * Where the Knowledge Graph is being served, bound to the language server connection that said so
 * (plan §8.1 / §11).
 *
 * The graph is **not** an LS-hosted webview. The server spawns a separately installed `gkg` binary,
 * reads the port it prints, and tells the client `http://localhost:<port>`. Until that happens — and
 * it never happens if `gkg` is not on the user's PATH — there is nothing to show.
 *
 * Two writers: the `ready` notification is the main path, and the `getUrl` request made when the
 * command runs is the compensation for a `ready` fired before the client was listening. Both pass
 * the session that reported the address *and* the current session, read by the caller — this object
 * deliberately knows nothing of the wrapper (§16.1). A report whose sender is not the current
 * session is dropped without touching the held value, so a late report from a dead connection can
 * never overwrite the live one (A14 / A25).
 *
 * There is no `clear()`: every read compares the held session with the caller's current one, so an
 * address from a closed connection simply stops answering. One residual race remains — a caller
 * that read `currentSession` just before a reconnect can still land its write after the new
 * connection's. That is fail-safe: the held session no longer matches, reads yield `null` (the
 * `NotAdvertised` page), and the next command's `getUrl` heals it.
 */
object KnowledgeGraphState {

  private class Held(val url: String, val session: LanguageServerSession)

  @Volatile
  private var held: Held? = null

  /**
   * Records [url] as reported by [senderSession]. Ignored when [url] is null or blank, when there is
   * no [currentSession], or when [senderSession] is not (by identity) [currentSession].
   */
  fun record(url: String?, senderSession: LanguageServerSession, currentSession: LanguageServerSession?) {
    if (url.isNullOrBlank() || currentSession == null || senderSession !== currentSession) return
    held = Held(url, senderSession)
  }

  /** The held address, only if it was reported by [currentSession]. */
  fun urlFor(currentSession: LanguageServerSession): String? =
    held?.takeIf { it.session === currentSession }?.url

  /**
   * The graph as a directly-addressed webview for [currentSession], or `null` while none is known.
   * Asked about every webview id by the resolver; answers only for [WEBVIEW_ID].
   */
  fun directWebviewFor(id: String, currentSession: LanguageServerSession): DirectWebview? =
    if (id == WEBVIEW_ID) urlFor(currentSession)?.let { DirectWebview(TITLE, it) } else null

  /**
   * The server's id for the graph plugin (`KNOWLEDGE_GRAPH_WEBVIEW_ID` in `src/knowledge_graph_plugin.ts`
   * of the bundled LS). A `PluginManager` plugin, hence never in `$/gitlab/webview-metadata`.
   */
  const val WEBVIEW_ID: String = "knowledge-graph"

  /** The server's own title for it (`KNOWLEDGE_GRAPH_WEBVIEW_TITLE`). */
  const val TITLE: String = "Knowledge Graph"

  /**
   * Shown instead of the page when no address is known. Inserted unescaped into an HTML `<p>`, so it
   * stays plain text without markup characters.
   */
  const val NOT_RUNNING_MESSAGE: String =
    "The GitLab Knowledge Graph is not running. It is served by the separately installed gkg " +
      "program: install it and make sure it is on your PATH, then restart the GitLab language server."
}
