package com.gitlab.eclipse.assignments

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.utils.logger
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.eclipse.ui.preferences.ScopedPreferenceStore

/**
 * Persists [ProjectAssignment]s in the existing preference store (design §11 / §15.1).
 *
 * All assignments live in ONE preference value as a JSON array — not a delimited string, because a
 * remote URL can contain any character (§11). Read, modify and persist happen under one lock:
 * assigning two different repositories concurrently would otherwise have both read the old value,
 * and the later save would drop one of them (§15.1 / A13).
 *
 * A failed save puts the preference value back to the old JSON before the lock is released (§15.1
 * R3-9 / A17). `setValue` changes the store's in-memory state immediately, so merely reporting the
 * failure would leave the new value there for the next save or the shutdown flush to persist —
 * "works this session, gone after a restart", which contradicts A7.
 */
class SelectedProjectStore(private val store: ScopedPreferenceStore = service()) {
  private val logger by lazy { logger<SelectedProjectStore>() }
  private val lock = Any()

  /** The fast path that keeps A8 true: with nothing assigned, callers do no work at all. */
  fun isEmpty(): Boolean = synchronized(lock) {
    store.getString(PreferenceConstants.SELECTED_PROJECTS).isNullOrBlank()
  }

  fun find(repositoryRootPath: String): ProjectAssignment? = synchronized(lock) {
    readAll()[repositoryRootPath]
  }

  /** Replaces this repository's assignment. False means nothing was persisted (A17). */
  fun put(assignment: ProjectAssignment): Boolean = synchronized(lock) {
    write(readAll() + (assignment.repositoryRootPath to assignment))
  }

  fun remove(repositoryRootPath: String): Boolean = synchronized(lock) {
    write(readAll() - repositoryRootPath)
  }

  private fun write(assignments: Map<String, ProjectAssignment>): Boolean {
    val previous = store.getString(PreferenceConstants.SELECTED_PROJECTS)
    return try {
      store.setValue(PreferenceConstants.SELECTED_PROJECTS, toJson(assignments))
      store.save()
      true
    } catch (e: Exception) {
      store.setValue(PreferenceConstants.SELECTED_PROJECTS, previous)
      // Type only: the value quotes repository paths and remote urls (A9).
      logger.error("Project assignment persistence failed: ${e.javaClass.name}")
      false
    }
  }

  /** A value we cannot parse is treated as empty: assignments are an override, so falling back to
   *  the normal resolution is always safe. */
  private fun readAll(): Map<String, ProjectAssignment> {
    val raw = store.getString(PreferenceConstants.SELECTED_PROJECTS)
    if (raw.isNullOrBlank()) return emptyMap()
    return try {
      JsonParser.parseString(raw).asJsonArray
        .mapNotNull { it.asJsonObject.toAssignment() }
        .associateBy { it.repositoryRootPath }
    } catch (e: Exception) {
      logger.warn("Project assignments could not be parsed and were ignored: ${e.javaClass.name}")
      emptyMap()
    }
  }

  private fun toJson(assignments: Map<String, ProjectAssignment>): String {
    val array = JsonArray()
    assignments.values.forEach { assignment ->
      array.add(
        JsonObject().apply {
          addProperty(REPOSITORY, assignment.repositoryRootPath)
          addProperty(REMOTE_URL, assignment.remoteUrl)
          addProperty(INSTANCE_URL, assignment.instanceUrl)
          addProperty(NAMESPACE_WITH_PATH, assignment.namespaceWithPath)
          addProperty(PROJECT_ID, assignment.projectId)
        },
      )
    }
    return array.toString()
  }

  private fun JsonObject.toAssignment(): ProjectAssignment? {
    val repository = stringOrNull(REPOSITORY) ?: return null
    val remoteUrl = stringOrNull(REMOTE_URL) ?: return null
    // §11.1: an assignment without an instance cannot be validated, so it is not an assignment.
    val instanceUrl = stringOrNull(INSTANCE_URL) ?: return null
    val namespaceWithPath = stringOrNull(NAMESPACE_WITH_PATH) ?: return null
    val projectId = get(PROJECT_ID)?.takeIf { !it.isJsonNull }?.asLong ?: return null
    return ProjectAssignment(repository, remoteUrl, instanceUrl, namespaceWithPath, projectId)
  }

  private fun JsonObject.stringOrNull(name: String): String? =
    get(name)?.takeIf { !it.isJsonNull }?.asString

  private companion object {
    const val REPOSITORY = "repository"
    const val REMOTE_URL = "remoteUrl"
    const val INSTANCE_URL = "instanceUrl"
    const val NAMESPACE_WITH_PATH = "namespaceWithPath"
    const val PROJECT_ID = "projectId"
  }
}
