package com.gitlab.eclipse.views.sidebar.queries

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** The query types VS Code's `gitlab.customQueries` knows; only [supported] ones are fetched (design §2). */
enum class CustomQueryType(val wire: String) {
  ISSUES("issues"),
  MERGE_REQUESTS("merge_requests"),
  EPICS("epics"),
  SNIPPETS("snippets"),
  VULNERABILITIES("vulnerabilities"),
  ;

  val supported: Boolean get() = this == ISSUES || this == MERGE_REQUESTS

  companion object {
    fun fromWire(s: String): CustomQueryType? = entries.firstOrNull { it.wire == s }
  }
}

/** Where a query is sent: instance-wide or the current editor's project (design §3). */
enum class QueryTarget(val wire: String) {
  INSTANCE("instance"),
  PROJECT("project"),
  ;

  companion object {
    fun fromWire(s: String): QueryTarget? = entries.firstOrNull { it.wire == s }
  }
}

/**
 * One stored query. [json] is a deep copy of the stored object (unknown keys preserved); [id] is the resolved
 * identity (design §5). [invalidElement] is a deep copy of a stored array element that is not a JSON object (then
 * [json] is empty); it is kept out of band so it is shown as invalid and written back unchanged.
 * Not a data class: identity by [id] is explicit, so equals/hashCode are not overridden.
 */
class CustomQuery(val id: String, val json: JsonObject, val invalidElement: JsonElement? = null) {
  val name: String? get() = string("name")?.trim()

  val typeWire: String? get() = string("type")

  /** The field's value when it is a JSON string, else null. */
  fun string(field: String): String? = json.stringField(field)
}

/** The field's value when it is a JSON string primitive, else null. */
internal fun JsonObject.stringField(field: String): String? =
  get(field)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

/** Why a stored value could not be used; a kind token only, safe to log (design §11). */
enum class UnreadableReason { MALFORMED_JSON, WRONG_SHAPE, UNSUPPORTED_VERSION }

/** The result of reading the stored preference value (design §5). */
sealed interface QueryConfig {
  val queries: List<CustomQuery>

  /** Nothing stored: the built-in defaults apply. */
  object Defaults : QueryConfig {
    override val queries: List<CustomQuery> get() = CustomQueryDefaults.queries
  }

  class Custom(override val queries: List<CustomQuery>) : QueryConfig

  /** A stored value that must not be used nor overwritten automatically; [rawText] is kept for "Edit as JSON…". */
  class Unreadable(val reason: UnreadableReason, val rawText: String) : QueryConfig {
    override val queries: List<CustomQuery> = emptyList()
  }
}
