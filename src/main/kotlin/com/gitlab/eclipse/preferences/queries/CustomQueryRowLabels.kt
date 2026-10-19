package com.gitlab.eclipse.preferences.queries

import com.gitlab.eclipse.views.sidebar.queries.CustomQuery
import com.gitlab.eclipse.views.sidebar.queries.CustomQueryType
import com.gitlab.eclipse.views.sidebar.queries.CustomQueryValidator
import com.gitlab.eclipse.views.sidebar.queries.ProblemSeverity
import com.gitlab.eclipse.views.sidebar.queries.QueryTarget
import com.google.gson.JsonElement

/** The column texts of the "Sidebar Queries" table (design §10). Pure: no SWT. */
object CustomQueryRowLabels {
  private const val INVALID = "(invalid)"

  /** Fields counted as filters in [summary]; `wip` and `draft` count once. */
  private val FILTER_FIELDS = listOf(
    "labels", "excludeLabels", "milestone", "excludeMilestone", "author", "excludeAuthor", "assignee",
    "excludeAssignee", "reviewer", "search", "excludeSearch", "createdAfter", "createdBefore", "updatedAfter",
    "updatedBefore", "confidential",
  )

  /** The trimmed name or "(unnamed query)", suffixed " (invalid)" when the query has an ERROR problem. */
  fun name(row: CustomQuery, allNames: List<String?>): String {
    val base = row.name?.takeIf { it.isNotEmpty() } ?: "(unnamed query)"
    val invalid = CustomQueryValidator.validate(row, allNames).any { it.severity == ProblemSeverity.ERROR }
    return if (invalid) "$base $INVALID" else base
  }

  fun type(row: CustomQuery): String {
    if (row.invalidElement != null) return INVALID
    return when (val type = CustomQueryValidator.effectiveType(row)) {
      CustomQueryType.ISSUES -> "Issues"
      CustomQueryType.MERGE_REQUESTS -> "Merge requests"
      null -> INVALID
      else -> "${type.wire} (not supported)"
    }
  }

  fun target(row: CustomQuery): String {
    if (row.invalidElement != null) return INVALID
    return when (CustomQueryValidator.effectiveTarget(row)) {
      QueryTarget.INSTANCE -> "All projects"
      QueryTarget.PROJECT -> "Current project"
      null -> INVALID
    }
  }

  /** `<scope>, <state>[, N filter(s)]` with the runtime defaults `all` / `opened`; empty for a non-object entry. */
  fun summary(row: CustomQuery): String {
    if (row.invalidElement != null) return ""
    val scope = row.string("scope")?.trim()?.takeIf { it.isNotEmpty() } ?: "all"
    val state = row.string("state")?.trim()?.takeIf { it.isNotEmpty() } ?: "opened"
    val draft = if (isSet(row.json.get("wip")) || isSet(row.json.get("draft"))) 1 else 0
    val filters = FILTER_FIELDS.count { isSet(row.json.get(it)) } + draft
    return when (filters) {
      0 -> "$scope, $state"
      1 -> "$scope, $state, 1 filter"
      else -> "$scope, $state, $filters filters"
    }
  }

  private fun isSet(element: JsonElement?): Boolean = when {
    element == null || element.isJsonNull -> false
    element.isJsonArray -> element.asJsonArray.size() > 0
    element.isJsonPrimitive && element.asJsonPrimitive.isString -> element.asString.isNotBlank()
    else -> true
  }
}
