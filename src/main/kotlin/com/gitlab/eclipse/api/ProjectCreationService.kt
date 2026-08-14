package com.gitlab.eclipse.api

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.navigation.PathSegmentEncoder
import com.gitlab.eclipse.utils.logger
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant

/** A project this plugin just created (design §10.1). Both clone urls are kept: the user picks. */
data class CreatedProject(
  val id: Long,
  val sshUrl: String,
  val httpUrl: String,
  val webUrl: String,
)

/**
 * A project that already occupies a namespace/path. [creatorId] and [createdAt] exist for the
 * recovery path only, which adopts a project solely when the current user created it at about the
 * time the intent was recorded (design §9.6 recovery step 3).
 */
data class ExistingProject(
  val id: Long,
  val creatorId: Long?,
  val createdAt: Instant?,
  val webUrl: String,
)

/**
 * Creates GitLab projects and resolves the ids the create call needs (design F6).
 *
 * Every call goes through [GitLabApiClient.captureConnectionIf], never `captureConnection()`: the
 * instance url has to be matched INSIDE the seqlock and BEFORE any credential is read, so a
 * mismatch cannot trigger an OAuth refresh and its side effects (issue #49).
 *
 * Blocking network I/O — call from a background thread.
 */
class ProjectCreationService(private val apiClient: GitLabApiClient = service()) {
  private val logger by lazy { logger<ProjectCreationService>() }

  /**
   * `GET /namespaces/{path}` → the numeric id `POST /projects` wants for a group. Null when the
   * connection gate rejected the instance.
   *
   * The whole namespace path is encoded as ONE segment: an unescaped slash would address a
   * different resource. Same treatment the project id gets elsewhere.
   */
  fun resolveNamespaceId(namespacePath: String, acceptInstanceUrl: (String) -> Boolean): Long? {
    val connection = apiClient.captureConnectionIf(acceptInstanceUrl) ?: run {
      logger.info("Namespace lookup skipped: the configured instance did not match.")
      return null
    }
    val encoded = PathSegmentEncoder.encodeSegment(namespacePath)
    val response = apiClient.fetchText("/namespaces/$encoded", connection)
    return JsonParser.parseString(response).asJsonObject.get("id")?.asLong
  }

  /**
   * `POST /projects` with `{path, namespace_id?, visibility}`. Null when the gate rejected the
   * instance.
   *
   * [namespaceId] null means the personal namespace, and then `namespace_id` is left out of the
   * body entirely rather than sent as null — which is what the reference extension does.
   */
  fun createProject(
    path: String,
    namespaceId: Long?,
    visibility: String,
    acceptInstanceUrl: (String) -> Boolean,
  ): CreatedProject? {
    val connection = apiClient.captureConnectionIf(acceptInstanceUrl) ?: run {
      logger.info("Project creation skipped: the configured instance did not match.")
      return null
    }
    val body = JsonObject().apply {
      addProperty("path", path)
      namespaceId?.let { addProperty("namespace_id", it) }
      addProperty("visibility", visibility)
    }
    val response = apiClient.postJson("/projects", body.toString(), connection)
    val json = JsonParser.parseString(response).asJsonObject
    return CreatedProject(
      id = json.get("id").asLong,
      sshUrl = json.stringOrEmpty("ssh_url_to_repo"),
      httpUrl = json.stringOrEmpty("http_url_to_repo"),
      webUrl = json.stringOrEmpty("web_url"),
    )
  }

  /**
   * `GET /projects/{namespace%2Fpath}` for the recovery path. Null when nothing is there, when the
   * gate rejected the instance, or when the lookup failed — all three mean "cannot adopt", which
   * is the safe answer.
   */
  fun findProject(namespaceWithPath: String, acceptInstanceUrl: (String) -> Boolean): ExistingProject? {
    val connection = apiClient.captureConnectionIf(acceptInstanceUrl) ?: run {
      logger.info("Project lookup skipped: the configured instance did not match.")
      return null
    }
    val encoded = PathSegmentEncoder.encodeSegment(namespaceWithPath)
    val response = try {
      apiClient.fetchText("/projects/$encoded", connection)
    } catch (e: GitLabApiException) {
      // Includes the 404 that means "the previous POST never created it". Status only (A9).
      logger.info("Project lookup returned HTTP ${e.statusCode}.")
      return null
    }
    val json = JsonParser.parseString(response).asJsonObject
    return ExistingProject(
      id = json.get("id").asLong,
      creatorId = json.get("creator_id")?.takeIf { !it.isJsonNull }?.asLong,
      createdAt = json.stringOrEmpty("created_at").toInstantOrNull(),
      webUrl = json.stringOrEmpty("web_url"),
    )
  }

  /** An unreadable timestamp must not fail the lookup: it only weakens the adoption test, and
   *  the caller falls back to asking the user. */
  private fun String.toInstantOrNull(): Instant? =
    try {
      Instant.parse(this)
    } catch (_: Exception) {
      null
    }

  private fun JsonObject.stringOrEmpty(name: String): String =
    get(name)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
}
