package com.gitlab.eclipse.api

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.snippets.SnippetPayload
import com.gitlab.eclipse.utils.logger
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Creates project snippets (design F1). */
class SnippetService(private val apiClient: GitLabApiClient = service()) {
  private val logger by lazy { logger<SnippetService>() }

  /**
   * `POST /projects/{id}/snippets`. Returns the new snippet's `web_url`, or null when the
   * connection gate rejected the instance or the response carried no url.
   *
   * The gate is [GitLabApiClient.captureConnectionIf], never `captureConnection()`: the instance
   * url must be matched INSIDE the seqlock and BEFORE any credential is read, so a mismatch
   * cannot trigger an OAuth refresh and its side effects (issue #49).
   *
   * Field order follows the API contract as recorded in the design: title, file_name, visibility,
   * content. `file_name` is snake_case on the wire.
   */
  fun create(
    projectId: Long,
    payload: SnippetPayload,
    acceptInstanceUrl: (String) -> Boolean,
  ): String? {
    val connection = apiClient.captureConnectionIf(acceptInstanceUrl) ?: run {
      // No url, no path: a mismatch is expected user configuration, not an error.
      logger.info("Snippet creation skipped: the configured instance did not match.")
      return null
    }
    val body = JsonObject().apply {
      addProperty("title", payload.title)
      addProperty("file_name", payload.fileName)
      addProperty("visibility", payload.visibility)
      addProperty("content", payload.content)
      // Absent rather than null when there is none: only patch snippets carry a description, and
      // sending an explicit null would set an empty one on plain snippets.
      payload.description?.let { addProperty("description", it) }
    }
    val response = apiClient.postJson("/projects/$projectId/snippets", body.toString(), connection)
    return JsonParser.parseString(response).asJsonObject.get("web_url")?.asString
  }
}
