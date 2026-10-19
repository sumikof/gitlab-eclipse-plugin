package com.gitlab.eclipse.views.sidebar.queries

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

enum class ProblemSeverity { ERROR, WARNING }

/** One validation finding; [message] names the field only and never contains the query's values (design §6). */
data class QueryProblem(val severity: ProblemSeverity, val field: String, val message: String)

/**
 * Validates one stored query field by field (design §6). Pure. An ERROR means the query is not run; a WARNING is
 * shown in the preference dialog only. Explicit JSON `null` counts as absent.
 */
object CustomQueryValidator {
  private const val MAX_RESULTS_LIMIT = 100
  private const val SCOPE_ALL = "all"

  private val ENUMS = mapOf(
    "scope" to listOf("assigned_to_me", "created_by_me", "dismissed", SCOPE_ALL),
    "state" to listOf("all", "opened", "closed"),
    "sort" to listOf("asc", "desc"),
    "orderBy" to listOf(
      "created_at", "updated_at", "priority", "due_date", "relative_position", "label_priority", "milestone_due",
      "popularity", "weight",
    ),
    "searchIn" to listOf("all", "title", "description"),
    "excludeSearchIn" to listOf("all", "title", "description"),
    "wip" to listOf("yes", "no"),
    "draft" to listOf("yes", "no"),
  )

  /** Blank values of these enum fields count as absent (design §3: empty → not sent). */
  private val BLANK_AS_ABSENT = setOf("sort", "orderBy")

  private val TEXT_FIELDS = listOf(
    "noItemText", "milestone", "excludeMilestone", "author", "excludeAuthor", "assignee", "excludeAssignee",
    "reviewer", "search", "excludeSearch",
  )

  private val DATE_FIELDS = listOf("createdAfter", "createdBefore", "updatedAfter", "updatedBefore")

  private val ISSUE_ONLY_FIELDS = listOf(
    "excludeLabels",
    "excludeMilestone",
    "excludeAuthor",
    "excludeAssignee",
    "excludeSearch",
    "excludeSearchIn",
    "confidential",
  )

  private val MR_ONLY_FIELDS = listOf("reviewer", "wip", "draft")

  private val ISSUE_ONLY_ORDER_BY = setOf("due_date", "relative_position", "weight")

  private val DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")
  private val DATE_TIME = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})$""")

  private class Rule(val field: String, val message: String, val valid: (JsonElement) -> Boolean)

  private val RULES: List<Rule> = buildList {
    this += Rule("type", "Type must be one of: ${CustomQueryType.entries.joinToString { it.wire }}.") { e ->
      e.text()?.let(CustomQueryType::fromWire) != null
    }
    this += Rule("target", "Target must be one of: ${QueryTarget.entries.joinToString { it.wire }}.") { e ->
      e.text()?.let(QueryTarget::fromWire) != null
    }
    TEXT_FIELDS.forEach { field -> this += Rule(field, "${label(field)} must be text.") { it.text() != null } }
    ENUMS.forEach { (field, values) ->
      this += Rule(field, "${label(field)} must be one of: ${values.joinToString()}.") { e ->
        val value = e.text()?.trim()
        value != null && (value in values || (value.isEmpty() && field in BLANK_AS_ABSENT))
      }
    }
    this += Rule("maxResults", "Max results must be a whole number from 1 to $MAX_RESULTS_LIMIT.", ::isMaxResults)
    DATE_FIELDS.forEach { field ->
      this += Rule(field, "${label(field)} must be an ISO 8601 date or date-time with offset.") { isDate(it.text()) }
    }
    listOf("labels", "excludeLabels").forEach { field ->
      this += Rule(field, "${label(field)} must be a list of texts without commas.") { e ->
        e.isJsonArray && e.asJsonArray.all { it.text()?.contains(',') == false }
      }
    }
    this += Rule("confidential", "Confidential must be true or false.") { e ->
      e.isJsonPrimitive && e.asJsonPrimitive.isBoolean
    }
  }

  /**
   * ERRORs first, then WARNINGs. [allNames] are the names of every query in the list, this one included,
   * compared after trim and case-sensitively; a name occurring twice or more is a duplicate. Unsupported types are
   * not validated (shown as unsupported roots instead).
   */
  fun validate(query: CustomQuery, allNames: List<String?> = emptyList()): List<QueryProblem> {
    if (query.invalidElement != null) {
      return listOf(QueryProblem(ProblemSeverity.ERROR, "query", "Query entry is not an object."))
    }
    if (effectiveType(query)?.supported == false) return emptyList()
    return errors(query) + warnings(query, allNames)
  }

  /** Absent → [CustomQueryType.MERGE_REQUESTS] (design §3); not a string or unknown → null. */
  fun effectiveType(query: CustomQuery): CustomQueryType? {
    val element = query.json.present("type") ?: return CustomQueryType.MERGE_REQUESTS
    return element.text()?.let(CustomQueryType::fromWire)
  }

  /** Absent → [QueryTarget.PROJECT] (design §3); not a string or unknown → null. */
  fun effectiveTarget(query: CustomQuery): QueryTarget? {
    val element = query.json.present("target") ?: return QueryTarget.PROJECT
    return element.text()?.let(QueryTarget::fromWire)
  }

  private fun errors(query: CustomQuery): List<QueryProblem> {
    val name = query.json.present("name")
    val nameProblem = when {
      name != null && name.text() == null -> "Name must be text."
      query.name.isNullOrEmpty() -> "Name must not be empty."
      else -> null
    }
    val ruleProblems = RULES.mapNotNull { rule ->
      val element = query.json.present(rule.field)
      if (element == null || rule.valid(element)) null else rule.field to rule.message
    }
    return (listOfNotNull(nameProblem?.let { "name" to it }) + ruleProblems)
      .map { (field, message) -> QueryProblem(ProblemSeverity.ERROR, field, message) }
  }

  private fun warnings(query: CustomQuery, allNames: List<String?>): List<QueryProblem> {
    val json = query.json
    val found = mutableListOf<Pair<String, String>>()
    when (effectiveType(query)) {
      CustomQueryType.MERGE_REQUESTS -> {
        ISSUE_ONLY_FIELDS.filter { json.present(it) != null }
          .forEach { found += it to "${label(it)} applies to issues only and is ignored for merge requests." }
        if (json.present("orderBy")?.text()?.trim() in ISSUE_ONLY_ORDER_BY) {
          found += "orderBy" to "This Order by value applies to issues only."
        }
      }
      CustomQueryType.ISSUES -> {
        MR_ONLY_FIELDS.filter { json.present(it) != null }
          .forEach { found += it to "${label(it)} applies to merge requests only and is ignored for issues." }
        if (effectiveTarget(query) == QueryTarget.INSTANCE) found += instanceIssueWarnings(json)
      }
      else -> Unit
    }
    val name = query.name
    if (!name.isNullOrEmpty() && allNames.count { it?.trim() == name } > 1) {
      found += "name" to "Another query has the same name."
    }
    return found.map { (field, message) -> QueryProblem(ProblemSeverity.WARNING, field, message) }
  }

  /** Instance-wide `GET /issues` limits (design §3, GitLab.com note and instance `not[]` attributes). */
  private fun instanceIssueWarnings(json: JsonObject): List<Pair<String, String>> {
    val scope = json.present("scope")
    val scopeIsAll = scope == null || scope.text()?.trim() == SCOPE_ALL
    val scopeWarning = "scope" to "Scope all for issues in All projects may be rejected on GitLab.com."
    return listOfNotNull(scopeWarning.takeIf { scopeIsAll }) +
      listOf("excludeSearch", "excludeSearchIn").filter { json.present(it) != null }
        .map { it to "${label(it)} is not supported for issues in All projects." }
  }

  private fun isMaxResults(element: JsonElement): Boolean {
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return false
    val value = element.asDouble
    return value >= 1 && value <= MAX_RESULTS_LIMIT && value == Math.floor(value)
  }

  private fun isDate(text: String?): Boolean =
    try {
      when {
        text == null -> false
        DATE.matches(text) -> LocalDate.parse(text) != null
        DATE_TIME.matches(text) -> OffsetDateTime.parse(text) != null
        else -> false
      }
    } catch (_: DateTimeParseException) {
      false
    }

  /** `maxResults` → `Max results`: a readable field label that never carries a value. */
  private fun label(field: String): String =
    field.replace(Regex("([A-Z])")) { " " + it.value.lowercase() }.replaceFirstChar { it.uppercase() }
}

/** The field's element unless it is absent or JSON `null`. */
private fun JsonObject.present(field: String): JsonElement? = get(field)?.takeUnless { it.isJsonNull }

/** The element's value when it is a JSON string, else null. */
private fun JsonElement.text(): String? = takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
