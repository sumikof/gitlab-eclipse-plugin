package com.gitlab.eclipse.views.sidebar.queries

import com.google.gson.JsonElement

const val CURRENT_USER_PLACEHOLDER = "<current_user>"

/** Encoded project id: numeric id as-is or path encoded like RepositoryContext.encodeProjectId. */
data class QueryProject(val encodedId: String)

/** What to do with one query (design §3); [Run.params] are raw values, encoding is the API client's job. */
sealed interface QueryPlan {
  data class Run(val path: String, val params: Map<String, String>, val maxItems: Int?) : QueryPlan

  object NeedsProject : QueryPlan

  object Unsupported : QueryPlan

  data class Invalid(val problem: QueryProblem) : QueryPlan
}

/**
 * Maps one stored query onto a GitLab REST list request per design §3 (VS Code `getIssuables`,
 * gitlab_service.ts:727-862). Pure. A value that is absent, JSON `null` or blank after trim is omitted (or defaulted);
 * every sent value is trimmed.
 */
object CustomQueryRequestBuilder {
  private const val SCOPE_ALL = "all"
  private const val SEARCH_IN_ALL = "title,description"
  private val USER_FIELDS = listOf("author", "assignee", "reviewer", "excludeAuthor", "excludeAssignee")
  private val KEPT_SCOPES = setOf("assigned_to_me", "created_by_me")
  private val ANY_OR_NONE = listOf("Any", "None")

  /** True when any user field holds [CURRENT_USER_PLACEHOLDER] (trimmed); the caller resolves `/user` first. */
  fun usesCurrentUser(query: CustomQuery): Boolean =
    USER_FIELDS.any { query.string(it)?.trim() == CURRENT_USER_PLACEHOLDER }

  /**
   * Invalid (first validator ERROR) → Unsupported type → NeedsProject → Run. Throws [IllegalStateException] when
   * the query [usesCurrentUser] and [currentUsername] is null (the caller must resolve it first).
   */
  fun plan(query: CustomQuery, project: QueryProject?, currentUsername: String?): QueryPlan {
    CustomQueryValidator.validate(query).firstOrNull { it.severity == ProblemSeverity.ERROR }
      ?.let { return QueryPlan.Invalid(it) }
    val type = checkNotNull(CustomQueryValidator.effectiveType(query))
    if (!type.supported) return QueryPlan.Unsupported
    val target = checkNotNull(CustomQueryValidator.effectiveTarget(query))
    if (target == QueryTarget.PROJECT && project == null) return QueryPlan.NeedsProject
    check(!usesCurrentUser(query) || currentUsername != null) { "current user not resolved" }
    val prefix = project?.takeIf { target == QueryTarget.PROJECT }?.let { "/projects/${it.encodedId}" }.orEmpty()
    val maxItems = query.json.get("maxResults")?.takeIf { it.isJsonPrimitive }?.asDouble?.toInt()
    return QueryPlan.Run("$prefix/${type.wire}", Params(query, currentUsername).build(type), maxItems)
  }

  private class Params(private val query: CustomQuery, private val currentUsername: String?) {
    private val map = LinkedHashMap<String, String>()

    fun build(type: CustomQueryType): Map<String, String> {
      val issues = type == CustomQueryType.ISSUES
      map["state"] = text("state") ?: "opened"
      map["scope"] = text("scope")?.takeIf { it in KEPT_SCOPES } ?: SCOPE_ALL
      put("author_username", user("author"))
      val assignee = user("assignee")
      when {
        anyOrNone(assignee) != null -> put("assignee_id", anyOrNone(assignee))
        issues -> put("assignee_username", assignee)
        else -> put("assignee_username[]", assignee)
      }
      if (!issues) {
        val reviewer = user("reviewer")
        val reviewerAnyOrNone = anyOrNone(reviewer)
        if (reviewerAnyOrNone != null) put("reviewer_id", reviewerAnyOrNone) else put("reviewer_username", reviewer)
      }
      put("in", searchIn("searchIn"))
      if (!issues) put("wip", text("wip") ?: text("draft"))
      put("labels", labels("labels"))
      put("milestone", text("milestone"))
      put("search", text("search"))
      put("created_before", text("createdBefore"))
      put("created_after", text("createdAfter"))
      put("updated_before", text("updatedBefore"))
      put("updated_after", text("updatedAfter"))
      put("order_by", text("orderBy"))
      put("sort", text("sort"))
      if (issues) putIssueOnly()
      return map
    }

    private fun putIssueOnly() {
      put("confidential", query.json.get("confidential")?.takeIf { it.isBoolean() }?.asBoolean?.toString())
      put("not[labels]", labels("excludeLabels"))
      put("not[milestone]", text("excludeMilestone"))
      put("not[author_username]", user("excludeAuthor"))
      put("not[assignee_username]", user("excludeAssignee"))
      put("not[search]", text("excludeSearch"))
      put("not[in]", searchIn("excludeSearchIn"))
    }

    private fun put(param: String, value: String?) {
      if (value != null) map[param] = value
    }

    private fun text(field: String): String? = query.string(field)?.trim()?.takeIf { it.isNotEmpty() }

    private fun user(field: String): String? =
      text(field)?.let { if (it == CURRENT_USER_PLACEHOLDER) checkNotNull(currentUsername) else it }

    private fun searchIn(field: String): String? = text(field)?.let { if (it == SCOPE_ALL) SEARCH_IN_ALL else it }

    private fun labels(field: String): String? =
      query.json.get(field)?.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() } }
        ?.takeIf { it.isNotEmpty() }?.joinToString(",")

    private fun anyOrNone(value: String?): String? = ANY_OR_NONE.firstOrNull { it.equals(value, ignoreCase = true) }
  }

  private fun JsonElement.isBoolean() = isJsonPrimitive && asJsonPrimitive.isBoolean
}
