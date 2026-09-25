package com.gitlab.eclipse.knowledgegraph

import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.lsp.plugins.PluginController
import com.gitlab.eclipse.lsp.plugins.annotations.PluginNotification

/**
 * Receives the Knowledge Graph plugin's `ready { url }` notification, which the server sends once
 * `gkg` has started and printed its port (plan §8.2).
 *
 * Runs on whatever thread `PluginMessageService.dispatch` hands it to (a `CompletableFuture.supplyAsync`
 * task, i.e. the common pool), possibly for a connection that has since been replaced. The sender's
 * session comes from [com.gitlab.eclipse.lsp.plugins.PluginRegistry] (last parameter); the current one
 * is read from [wrapper] exactly once per notification and handed to [KnowledgeGraphState.record],
 * which drops the report unless the two are the same connection (A14 / A25). No snapshot means no
 * current connection, so nothing is recorded.
 *
 * Logs nothing: the only interesting value is the address, which must never reach the log (§15).
 */
class KnowledgeGraphController(
  private val wrapper: GitLabLanguageServerWrapper
) : PluginController(KnowledgeGraphState.WEBVIEW_ID) {

  /**
   * [payload] is `Any?` rather than a DTO so that parsing can never fail (§15): Gson turns any JSON
   * value — object, array, string, number — into an `Object`, so `PluginMessageService` never reaches
   * the parse-failure branch that WARNs the whole payload, i.e. the address. A notification with no
   * payload never gets this far: `PluginMessageService` drops it with a WARN that names only the
   * route. The shape is checked afterwards by [knowledgeGraphUrlOf], which yields `null` for anything
   * but an object with a non-blank string `url`.
   */
  @PluginNotification("ready")
  fun ready(payload: Any?, session: LanguageServerSession) {
    KnowledgeGraphState.record(knowledgeGraphUrlOf(payload), session, wrapper.currentSnapshot?.session)
  }
}
