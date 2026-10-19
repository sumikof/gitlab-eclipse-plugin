package com.gitlab.eclipse.preferences.queries

import com.gitlab.eclipse.views.sidebar.queries.CustomQuery
import com.gitlab.eclipse.views.sidebar.queries.CustomQueryCodec
import com.gitlab.eclipse.views.sidebar.queries.CustomQueryDefaults
import com.gitlab.eclipse.views.sidebar.queries.QueryConfig
import com.google.gson.JsonObject
import java.util.Collections
import java.util.UUID

/** What OK / Apply does with the `gitlab.sidebar.customQueries` preference (design §10). */
sealed interface SaveAction {
  /** Leave the stored value as it is. */
  object Nothing : SaveAction

  /** Remove the stored value, so the built-in defaults apply. */
  object SetToDefault : SaveAction

  /** Store [value] (encoded by [CustomQueryCodec.encode]). */
  class Write(val value: String) : SaveAction
}

/**
 * The query table of the "Sidebar Queries" preference page (design §10). Pure: no SWT, no preference access.
 *
 * An [QueryConfig.Unreadable] initial config is read-only with no rows: list edits are ignored and nothing is saved
 * until [restoreDefaults] or [replaceAllFromJson] is used, so the stored value is never replaced implicitly. Saving
 * compares the encoded rows with the encoded initial rows, so edits that end equal to the start save nothing.
 * Out-of-range indices (and moves past the ends) are no-ops.
 */
class CustomQueryListModel(
  private val initial: QueryConfig,
  private val newId: () -> String = { UUID.randomUUID().toString() },
) {
  private val current = initial.queries.toMutableList()
  private var restored = false
  private var replaced = false

  /** The encoded initial rows; null for an unreadable config (any replacement differs from it). */
  private val initialEncoded: String? =
    if (initial is QueryConfig.Unreadable) null else CustomQueryCodec.encode(current)

  val rows: List<CustomQuery> get() = current.toList()

  /** True while the initial config is unreadable and neither restored nor replaced from JSON. */
  val isReadOnly: Boolean get() = initial is QueryConfig.Unreadable && !restored && !replaced

  /** The unreadable stored text for "Edit as JSON…" while [isReadOnly], else null. */
  val unreadableText: String? get() = (initial as? QueryConfig.Unreadable)?.rawText?.takeIf { isReadOnly }

  val isDirty: Boolean get() = saveAction() != SaveAction.Nothing

  /** Appends [query]; a blank or already used id is replaced by a new one. */
  fun add(query: CustomQuery) {
    if (isReadOnly) return
    val id = query.id.takeIf { it.isNotBlank() && current.none { row -> row.id == it } } ?: newId()
    current += CustomQuery(id, query.json, query.invalidElement)
  }

  /** Replaces the row at [index] with [query]'s content; the row keeps its id. */
  fun replace(index: Int, query: CustomQuery) = edit(index) {
    current[index] = CustomQuery(current[index].id, query.json, query.invalidElement)
  }

  /** Inserts a deep copy after [index] with a new id and the name suffixed " (copy)". */
  fun duplicate(index: Int) = edit(index) {
    val source = current[index]
    val copy = source.invalidElement?.let { CustomQuery(newId(), JsonObject(), it.deepCopy()) }
      ?: CustomQuery(
        newId(),
        source.json.deepCopy().apply { addProperty(NAME, source.string(NAME).orEmpty() + COPY_SUFFIX) },
      )
    current.add(index + 1, copy)
  }

  fun remove(index: Int) = edit(index) { current.removeAt(index) }

  fun moveUp(index: Int) = edit(index) { if (index > 0) Collections.swap(current, index, index - 1) }

  fun moveDown(index: Int) = edit(index) { if (index < current.lastIndex) Collections.swap(current, index, index + 1) }

  /** Shows the built-in defaults; saving then removes the stored value unless the rows are edited again. */
  fun restoreDefaults() {
    current.clear()
    current += CustomQueryDefaults.queries
    restored = true
    replaced = false
  }

  /** Replaces every row with [queries] parsed from "Edit as JSON…"; saving writes them. */
  fun replaceAllFromJson(queries: List<CustomQuery>) {
    current.clear()
    current += queries
    replaced = true
    restored = false
  }

  fun saveAction(): SaveAction {
    if (isReadOnly) return SaveAction.Nothing
    val value = CustomQueryCodec.encode(current)
    return when {
      value == initialEncoded -> SaveAction.Nothing
      restored && value == CustomQueryCodec.encode(CustomQueryDefaults.queries) -> SaveAction.SetToDefault
      else -> SaveAction.Write(value)
    }
  }

  private fun edit(index: Int, change: () -> Unit) {
    if (!isReadOnly && index in current.indices) change()
  }

  private companion object {
    const val NAME = "name"
    const val COPY_SUFFIX = " (copy)"
  }
}
