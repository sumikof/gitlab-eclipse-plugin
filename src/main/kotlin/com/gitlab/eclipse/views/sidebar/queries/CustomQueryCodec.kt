package com.gitlab.eclipse.views.sidebar.queries

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

/**
 * Reads and writes the `gitlab.sidebar.customQueries` value (design §5). Pure: no logging, no preference access.
 *
 * Stored as `{"version":1,"queries":[...]}`; a bare array is accepted as version 1 (hand edit / VS Code paste).
 */
object CustomQueryCodec {
  const val VERSION = 1

  private const val VERSION_FIELD = "version"
  private const val QUERIES_FIELD = "queries"
  private const val ID_FIELD = "id"

  fun decode(stored: String?): QueryConfig {
    if (stored.isNullOrBlank()) return QueryConfig.Defaults
    val root = parse(stored) ?: return QueryConfig.Unreadable(UnreadableReason.MALFORMED_JSON, stored)
    val array = when {
      root.isJsonArray -> root.asJsonArray
      root.isJsonObject -> {
        val version = versionOf(root.asJsonObject)
          ?: return QueryConfig.Unreadable(UnreadableReason.WRONG_SHAPE, stored)
        if (version > VERSION) return QueryConfig.Unreadable(UnreadableReason.UNSUPPORTED_VERSION, stored)
        root.asJsonObject.get(QUERIES_FIELD)?.takeIf { it.isJsonArray }?.asJsonArray
          ?: return QueryConfig.Unreadable(UnreadableReason.WRONG_SHAPE, stored)
      }
      else -> return QueryConfig.Unreadable(UnreadableReason.WRONG_SHAPE, stored)
    }
    return QueryConfig.Custom(toQueries(array))
  }

  /** Writes `{"version":1,"queries":[...]}`, each object carrying its resolved [CustomQuery.id]. */
  fun encode(queries: List<CustomQuery>): String {
    val array = JsonArray()
    queries.forEach { query ->
      val invalid = query.json.get(INVALID_ELEMENT_FIELD)
      array.add(invalid?.deepCopy() ?: query.json.deepCopy().apply { addProperty(ID_FIELD, query.id) })
    }
    return JsonObject().apply {
      addProperty(VERSION_FIELD, VERSION)
      add(QUERIES_FIELD, array)
    }.toString()
  }

  /** Parses text typed into "Edit as JSON…"; blank text or an unreadable value is a failure. */
  fun decodeText(text: String): Result<List<CustomQuery>> =
    when (val config = decode(text)) {
      is QueryConfig.Custom -> Result.success(config.queries)
      is QueryConfig.Unreadable -> Result.failure(IllegalArgumentException(config.reason.name))
      QueryConfig.Defaults -> Result.failure(IllegalArgumentException("blank"))
    }

  private fun parse(text: String): JsonElement? =
    try {
      JsonParser.parseString(text)
    } catch (_: JsonParseException) {
      null
    }

  /** A positive integral version number, else null (missing, string, fraction …). */
  private fun versionOf(root: JsonObject): Int? {
    val primitive = root.get(VERSION_FIELD)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
    if (!primitive.isNumber) return null
    val number = primitive.asBigDecimal
    return number.toInt().takeIf { number.signum() > 0 && number.stripTrailingZeros().scale() <= 0 }
  }

  /** Resolves ids: stored trimmed non-blank `id`, else `entry-<index>`; duplicates get `#2`, `#3` … in order. */
  private fun toQueries(array: JsonArray): List<CustomQuery> {
    val used = mutableSetOf<String>()
    return array.mapIndexed { index, element ->
      val json = if (element.isJsonObject) {
        element.asJsonObject.deepCopy()
      } else {
        JsonObject().apply { add(INVALID_ELEMENT_FIELD, element.deepCopy()) }
      }
      val stored = json.get(ID_FIELD)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()
      val base = stored?.takeIf { it.isNotEmpty() } ?: "entry-$index"
      val id = if (base !in used) base else generateSequence(2) { it + 1 }.map { "$base#$it" }.first { it !in used }
      used += id
      CustomQuery(id, json)
    }
  }
}
