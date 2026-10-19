package com.gitlab.eclipse.preferences.queries

import com.gitlab.eclipse.views.sidebar.queries.CustomQuery
import com.gitlab.eclipse.views.sidebar.queries.CustomQueryCodec
import com.gitlab.eclipse.views.sidebar.queries.UnreadableReason
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser

/** The text shown and parsed by "Edit as JSON…" (design §10). Pure: no SWT. */
object CustomQueriesJsonText {
  private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

  /** [rows] as indented `{"version":1,"queries":[...]}` (the stored format, ids included). */
  fun format(rows: List<CustomQuery>): String = gson.toJson(JsonParser.parseString(CustomQueryCodec.encode(rows)))

  /** The queries in [text], or the user-facing reason it cannot be used. */
  fun parse(text: String): Result<List<CustomQuery>> =
    CustomQueryCodec.decodeText(text).fold(
      onSuccess = { Result.success(it) },
      onFailure = { Result.failure(IllegalArgumentException(errorText(it.message))) },
    )

  private fun errorText(reason: String?): String = when (reason) {
    UnreadableReason.MALFORMED_JSON.name -> "The text is not valid JSON."
    UnreadableReason.WRONG_SHAPE.name ->
      "Expected {\"version\": 1, \"queries\": [...]} or a JSON array of queries."
    UnreadableReason.UNSUPPORTED_VERSION.name -> "This version of the queries format is not supported."
    else -> "Enter the queries as JSON, for example []."
  }
}
