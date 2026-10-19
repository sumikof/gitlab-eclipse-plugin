package com.gitlab.eclipse.preferences.queries

import com.gitlab.eclipse.views.sidebar.queries.CustomQuery
import com.gitlab.eclipse.views.sidebar.queries.CustomQueryType
import com.gitlab.eclipse.views.sidebar.queries.CustomQueryValidator
import com.gitlab.eclipse.views.sidebar.queries.QueryProblem
import com.gitlab.eclipse.views.sidebar.queries.QueryTarget
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * The edit form of one sidebar query (design §3, §10). Pure: no SWT. Works on a deep copy of the query's JSON, so
 * unknown keys survive and the original is never mutated.
 *
 * Text forms: blank text removes the key; labels are `"a, b"` ↔ `["a","b"]` (trimmed, empties dropped); max results
 * is integer text; draft is `""` / `"yes"` / `"no"` (a stored `wip` is kept as is); confidential is `""` / `"yes"` /
 * `"no"` ↔ absent / `true` / `false`; enums are their wire text. Text that cannot be converted is stored as a JSON
 * string, so [problems] reports it. Setting a field to the text [get] shows is a no-op, so an unedited field keeps its
 * stored JSON exactly.
 */
class CustomQueryFormModel(query: CustomQuery?) {
  private enum class Kind { TEXT, LABELS, NUMBER, BOOLEAN }

  private val originalInvalid: JsonElement? = query?.invalidElement
  private val json: JsonObject = query?.json?.deepCopy() ?: JsonObject().apply {
    addProperty(TYPE, CustomQueryType.MERGE_REQUESTS.wire)
    addProperty("target", QueryTarget.PROJECT.wire)
    addProperty("state", "opened")
  }
  private var edited = false

  /** The field's text form; `""` when absent or JSON `null`. */
  fun get(field: String): String {
    val kind = kindOf(field)
    val element = json.get(field)?.takeUnless { it.isJsonNull } ?: return ""
    return when (kind) {
      Kind.TEXT -> element.show()
      Kind.LABELS -> if (element.isJsonArray) element.asJsonArray.joinToString(", ") { it.show() } else element.show()
      Kind.NUMBER -> element.showNumber()
      Kind.BOOLEAN -> element.showBoolean()
    }
  }

  fun set(field: String, text: String) {
    val kind = kindOf(field)
    if (text == get(field)) return
    edited = true
    val value = if (text.isBlank()) null else parse(kind, text)
    if (value == null) json.remove(field) else json.add(field, value)
  }

  /** False for issue-only fields of merge request queries, merge-request-only fields of issue queries, and the type
   *  of an unsupported query; a disabled field keeps its value. */
  fun isEnabled(field: String): Boolean {
    kindOf(field)
    val type = CustomQueryValidator.effectiveType(CustomQuery("", json))
    return when (field) {
      TYPE -> type?.supported != false
      in ISSUE_ONLY -> type != CustomQueryType.MERGE_REQUESTS
      in MR_ONLY -> type != CustomQueryType.ISSUES
      else -> true
    }
  }

  /** [CustomQueryValidator.validate] of the current values; [allNames] as there. */
  fun problems(allNames: List<String?>): List<QueryProblem> = CustomQueryValidator.validate(toQuery(""), allNames)

  /** The current values under [id]; an invalid (non-object) original is kept unchanged while nothing was set. */
  fun toQuery(id: String): CustomQuery =
    if (!edited && originalInvalid != null) {
      CustomQuery(id, JsonObject(), originalInvalid.deepCopy())
    } else {
      CustomQuery(id, json.deepCopy())
    }

  private fun kindOf(field: String): Kind = KINDS[field] ?: throw IllegalArgumentException("Unknown field: $field")

  private fun parse(kind: Kind, text: String): JsonElement? {
    val trimmed = text.trim()
    return when (kind) {
      Kind.TEXT -> JsonPrimitive(text)
      Kind.LABELS -> trimmed.split(',').map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
        ?.let { labels -> JsonArray().also { array -> labels.forEach { array.add(it) } } }
      Kind.NUMBER -> trimmed.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(text)
      Kind.BOOLEAN -> when (trimmed) {
        YES -> JsonPrimitive(true)
        NO -> JsonPrimitive(false)
        else -> JsonPrimitive(text)
      }
    }
  }

  companion object {
    private const val TYPE = "type"
    private const val YES = "yes"
    private const val NO = "no"

    private val ISSUE_ONLY = setOf(
      "excludeLabels",
      "excludeMilestone",
      "excludeAuthor",
      "excludeAssignee",
      "excludeSearch",
      "excludeSearchIn",
      "confidential",
    )
    private val MR_ONLY = setOf("reviewer", "draft")

    private val KINDS: Map<String, Kind> = linkedMapOf(
      "name" to Kind.TEXT, TYPE to Kind.TEXT, "target" to Kind.TEXT, "scope" to Kind.TEXT, "state" to Kind.TEXT,
      "labels" to Kind.LABELS, "excludeLabels" to Kind.LABELS, "milestone" to Kind.TEXT,
      "excludeMilestone" to Kind.TEXT, "author" to Kind.TEXT, "excludeAuthor" to Kind.TEXT, "assignee" to Kind.TEXT,
      "excludeAssignee" to Kind.TEXT, "reviewer" to Kind.TEXT, "search" to Kind.TEXT, "searchIn" to Kind.TEXT,
      "excludeSearch" to Kind.TEXT, "excludeSearchIn" to Kind.TEXT, "createdAfter" to Kind.TEXT,
      "createdBefore" to Kind.TEXT, "updatedAfter" to Kind.TEXT, "updatedBefore" to Kind.TEXT, "draft" to Kind.TEXT,
      "confidential" to Kind.BOOLEAN, "orderBy" to Kind.TEXT, "sort" to Kind.TEXT, "maxResults" to Kind.NUMBER,
      "noItemText" to Kind.TEXT,
    )

    /** Every form field, in dialog order (design §10). */
    val FIELDS: List<String> = KINDS.keys.toList()

    private const val MAX_EXACT_DIGITS = 18

    private fun JsonElement.show(): String =
      if (isJsonPrimitive && asJsonPrimitive.isString) asString else toString()

    /** An integral number as integer text (`20.0` → `20`); anything else as stored. */
    private fun JsonElement.showNumber(): String {
      if (!isJsonPrimitive || !asJsonPrimitive.isNumber) return show()
      val number = asString.toBigDecimalOrNull()?.stripTrailingZeros() ?: return asString
      val integral = number.scale() <= 0 && number.precision() - number.scale() <= MAX_EXACT_DIGITS
      return if (integral) number.toBigIntegerExact().toString() else asString
    }

    private fun JsonElement.showBoolean(): String =
      if (isJsonPrimitive && asJsonPrimitive.isBoolean) (if (asBoolean) YES else NO) else show()
  }
}
