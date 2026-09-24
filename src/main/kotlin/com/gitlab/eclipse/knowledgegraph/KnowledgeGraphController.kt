package com.gitlab.eclipse.knowledgegraph

import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.lsp.plugins.PluginController
import com.gitlab.eclipse.lsp.plugins.annotations.PluginNotification

/**
 * Receives the Knowledge Graph plugin's `ready { url }` notification, which the server sends once
 * `gkg` has started and printed its port (plan §8.2).
 *
 * Runs on an lsp4j dispatch thread, possibly for a connection that has since been replaced. The
 * sender's session comes from [com.gitlab.eclipse.lsp.plugins.PluginRegistry] (last parameter); the
 * current one is read from [wrapper] exactly once per notification and handed to
 * [KnowledgeGraphState.record], which drops the report unless the two are the same connection
 * (A14 / A25). No snapshot means no current connection, so nothing is recorded.
 *
 * Logs nothing: the only interesting value is the address, which must never reach the log (§15).
 */
class KnowledgeGraphController(
  private val wrapper: GitLabLanguageServerWrapper
) : PluginController(KnowledgeGraphState.WEBVIEW_ID) {

  /** Nullable because a JSON `null` payload parses to `null`; that is simply a report of nothing. */
  @PluginNotification("ready")
  fun ready(payload: KnowledgeGraphReady?, session: LanguageServerSession) {
    val currentSession = wrapper.currentSnapshot?.session
    KnowledgeGraphState.record(payload?.url as? String, session, currentSession)
  }
}

/**
 * The `ready` payload, typed so loosely that Gson cannot fail on any JSON object: a parse failure
 * would make `PluginMessageService` WARN the whole payload, i.e. the address (§15). The type check
 * happens afterwards, in [KnowledgeGraphController.ready].
 */
data class KnowledgeGraphReady(val url: Any? = null)
