package com.gitlab.eclipse.views.sidebar.queries

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.math.BigDecimal

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

  /** Gson's JsonParser is lenient (unquoted keys, comments accepted); [encode] writes normalised JSON. */
  fun decode(stored: String?): QueryConfig {
    if (stored.isNullOrBlank()) return QueryConfig.Defaults
    val root = parse(stored) ?: return QueryConfig.Unreadable(UnreadableReason.MALFORMED_JSON, stored)
    val array = when {
      root.isJsonArray -> root.asJsonArray
      root.isJsonObject -> {
        val version = versionOf(root.asJsonObject)
          ?: return QueryConfig.Unreadable(UnreadableReason.WRONG_SHAPE, stored)
        if (version > BigDecimal(VERSION)) return QueryConfig.Unreadable(UnreadableReason.UNSUPPORTED_VERSION, stored)
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
      array.add(query.invalidElement?.deepCopy() ?: query.json.deepCopy().apply { addProperty(ID_FIELD, query.id) })
    }
    return JsonObject().apply {
      addProperty(VERSION_FIELD, VERSION)
      add(QUERIES_FIELD, array)
    }.toString()
  }

  /**
   * Parses text typed into "Edit as JSON…"; blank text or an unreadable value is a failure. Lenient like [decode]
   * (unquoted keys, comments accepted); [encode] writes normalised JSON.
   */
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

  /** A positive integral version number of any size, else null (missing, string, zero, negative, fraction …). */
  private fun versionOf(root: JsonObject): BigDecimal? {
    val primitive = root.get(VERSION_FIELD)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
    if (!primitive.isNumber) return null
    // Parsed from the raw text (Gson's asBigDecimal throws on a large exponent) and compared as BigDecimal: never
    // truncated to Int, never expanded. A number Java cannot represent at all (exponent beyond Int) reads as null.
    val number = primitive.asString.toBigDecimalOrNull() ?: return null
    return number.takeIf { it.signum() > 0 && it.stripTrailingZeros().scale() <= 0 }
  }

  /** Resolves ids: stored trimmed non-blank `id`, else `entry-<index>`; duplicates get `#2`, `#3` … in order. */
  private fun toQueries(array: JsonArray): List<CustomQuery> {
    val used = mutableSetOf<String>()
    return array.mapIndexed { index, element ->
      val json = if (element.isJsonObject) element.asJsonObject.deepCopy() else JsonObject()
      val invalid = if (element.isJsonObject) null else element.deepCopy()
      val stored = json.stringField(ID_FIELD)?.trim()
      val base = stored?.takeIf { it.isNotEmpty() } ?: "entry-$index"
      val id = if (base !in used) base else generateSequence(2) { it + 1 }.map { "$base#$it" }.first { it !in used }
      used += id
      CustomQuery(id, json, invalid)
    }
  }
}
