package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.ci.actions.normalizeInstanceUrl
import com.gitlab.eclipse.inject.service

/**
 * Where Quick Chat gets its connection (design §9.2.3). Both calls block (an OAuth refresh may run)
 * and must be made inside `runInterruptible`.
 */
interface QuickChatConnections {
  /** The current connection, for a conversation not bound to an instance yet. */
  fun capture(): ConnectionSnapshot

  /** The current connection only if it is still [instanceUrl] (compared normalized); else null. */
  fun captureIf(instanceUrl: String): ConnectionSnapshot?
}

/** [QuickChatConnections] over [GitLabApiClient]; a mismatch is detected before the token is read. */
class ApiClientQuickChatConnections(
  private val client: GitLabApiClient = service(),
) : QuickChatConnections {
  override fun capture(): ConnectionSnapshot = client.captureConnection()

  override fun captureIf(instanceUrl: String): ConnectionSnapshot? {
    val wanted = normalizeInstanceUrl(instanceUrl)
    return client.captureConnectionIf { normalizeInstanceUrl(it) == wanted }
  }
}
