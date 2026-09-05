package com.gitlab.eclipse.publish

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.utils.logger
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.time.Instant

/**
 * Persists [PublishRecord]s in the existing preference store (design §11 / §15.1).
 *
 * All records live in ONE preference value as a JSON array — not a delimited string, because a
 * remote URL can contain any character (§11). Read, modify and persist happen under one lock: two
 * concurrent publishes of different repositories would otherwise both read the old value and the
 * later save would drop one of them (§15.1).
 *
 * When persisting fails, the preference value is put back to the old JSON before the lock is
 * released (§15.1 R3-9). `setValue` changes the store's in-memory state immediately, so merely
 * reporting the failure would leave the new value inside the store, where the next save or the
 * shutdown flush would persist a record already reported as failed.
 */
class PublishRecordStore(private val store: ScopedPreferenceStore = service()) {
  private val logger by lazy { logger<PublishRecordStore>() }
  private val lock = Any()

  fun find(repositoryRootPath: String): PublishRecord? = synchronized(lock) {
    readAll()[repositoryRootPath]
  }

  /** Replaces this repository's record. False means nothing was persisted — do not proceed. */
  fun put(record: PublishRecord): Boolean = synchronized(lock) {
    write(readAll() + (record.repositoryRootPath to record))
  }

  fun remove(repositoryRootPath: String): Boolean = synchronized(lock) {
    write(readAll() - repositoryRootPath)
  }

  private fun write(records: Map<String, PublishRecord>): Boolean {
    val previous = store.getString(PreferenceConstants.PUBLISH_RECORDS)
    return try {
      store.setValue(PreferenceConstants.PUBLISH_RECORDS, toJson(records))
      store.save()
      true
    } catch (e: Exception) {
      store.setValue(PreferenceConstants.PUBLISH_RECORDS, previous)
      // Type only: the value quotes repository paths and remote urls (A9).
      logger.error("Publish record persistence failed: ${e.javaClass.name}")
      false
    }
  }

  /** A value we cannot parse is treated as empty: refusing to publish over it helps nobody. */
  private fun readAll(): Map<String, PublishRecord> {
    val raw = store.getString(PreferenceConstants.PUBLISH_RECORDS)
    if (raw.isNullOrBlank()) return emptyMap()
    return try {
      JsonParser.parseString(raw).asJsonArray
        .mapNotNull { it.asJsonObject.toRecord() }
        .associateBy { it.repositoryRootPath }
    } catch (e: Exception) {
      logger.warn("Publish records could not be parsed and were ignored: ${e.javaClass.name}")
      emptyMap()
    }
  }

  private fun toJson(records: Map<String, PublishRecord>): String {
    val array = JsonArray()
    records.values.forEach { array.add(it.toJson()) }
    return array.toString()
  }

  private fun PublishRecord.toJson(): JsonObject = JsonObject().apply {
    addProperty(KIND, if (this@toJson is PublishRecord.State) KIND_STATE else KIND_INTENT)
    addProperty(REPOSITORY, repositoryRootPath)
    addProperty(INSTANCE_URL, instanceUrl)
    when (this@toJson) {
      is PublishRecord.Intent -> {
        addProperty(NAMESPACE, namespacePath)
        addProperty(PROJECT_PATH, projectPath)
        // Epoch millis, not a formatted string: no locale or zone to get wrong on the way back.
        addProperty(RECORDED_AT, recordedAt.toEpochMilli())
      }
      is PublishRecord.State -> {
        addProperty(NAMESPACE, namespacePath)
        addProperty(PROJECT_PATH, projectPath)
        addProperty(PROJECT_ID, projectId)
        addProperty(REMOTE_URL, normalizedRemoteUrl)
        addProperty(REMOTE_NAME, remoteName)
        addProperty(WEB_URL, projectWebUrl)
      }
    }
  }

  private fun JsonObject.toRecord(): PublishRecord? {
    val repository = stringOrNull(REPOSITORY) ?: return null
    val instanceUrl = stringOrNull(INSTANCE_URL) ?: return null
    val namespace = stringOrNull(NAMESPACE).orEmpty()
    val projectPath = stringOrNull(PROJECT_PATH).orEmpty()
    // §11: should both ever be observed for one repository — hand editing, a future bug — the
    // confirmed state wins over the unconfirmed intent.
    return if (stringOrNull(KIND) == KIND_STATE) {
      PublishRecord.State(
        repositoryRootPath = repository,
        instanceUrl = instanceUrl,
        namespacePath = namespace,
        projectPath = projectPath,
        projectId = get(PROJECT_ID)?.asLong ?: return null,
        normalizedRemoteUrl = stringOrNull(REMOTE_URL) ?: return null,
        remoteName = stringOrNull(REMOTE_NAME) ?: return null,
        projectWebUrl = stringOrNull(WEB_URL).orEmpty(),
      )
    } else {
      PublishRecord.Intent(
        repositoryRootPath = repository,
        instanceUrl = instanceUrl,
        namespacePath = namespace,
        projectPath = projectPath,
        recordedAt = Instant.ofEpochMilli(get(RECORDED_AT)?.asLong ?: return null),
      )
    }
  }

  private fun JsonObject.stringOrNull(name: String): String? =
    get(name)?.takeIf { !it.isJsonNull }?.asString

  private companion object {
    const val KIND = "kind"
    const val KIND_INTENT = "intent"
    const val KIND_STATE = "state"
    const val REPOSITORY = "repository"
    const val INSTANCE_URL = "instanceUrl"
    const val NAMESPACE = "namespace"
    const val PROJECT_PATH = "projectPath"
    const val RECORDED_AT = "recordedAt"
    const val PROJECT_ID = "projectId"
    const val REMOTE_URL = "remoteUrl"
    const val REMOTE_NAME = "remoteName"
    const val WEB_URL = "webUrl"
  }
}
