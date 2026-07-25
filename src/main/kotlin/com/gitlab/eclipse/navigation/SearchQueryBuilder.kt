package com.gitlab.eclipse.navigation

import java.net.URLEncoder

/**
 * Kotlin port of gitlab-workflow search_input.ts `parseQuery` (:58-137) + createQueryString.
 * Returns "" or "?<encoded>". `noteableType` is "issues" or "merge_requests".
 */
object SearchQueryBuilder {

  private val TOKEN_SPLIT_REGEX = Regex("\\s[a-z]*:", setOf(RegexOption.IGNORE_CASE))

  fun parseQuery(query: String, noteableType: String): String {
    // showSearchInputFor guards `if (!query) return;` before calling parseQuery (search_input.ts:159);
    // folded into this pure function since it is the only caller-side precondition.
    if (query.isEmpty()) return ""

    val tokens = tokenize(query)
    val params = LinkedHashMap<String, String?>()
    val labels = mutableListOf<String>()

    if (tokens.size == 1 && tokens[0].getOrNull(1) == null) {
      params["search"] = tokens[0][0] // :68-72 basic free text
    } else {
      tokens.forEach { applyToken(it, noteableType, params, labels) }
    }
    if (labels.isNotEmpty()) params["labels"] = labels.joinToString(",")
    return createQueryString(params)
  }

  /** Tokenizer, :62-66. */
  private fun tokenize(query: String): List<List<String>> = query
    .replace(": ", ":")
    .replace(TOKEN_SPLIT_REGEX) { "\n${it.value}" }
    .split("\n")
    .map { it.trim().split(":") }

  private fun applyToken(t: List<String>, noteableType: String, params: MutableMap<String, String?>, labels: MutableList<String>) {
    val token = t[0]
    val value = t.getOrNull(1)
    when (token) {
      "labels" -> if (value != null) labels.addAll(value.replace(", ", ",").split(",")) // :79-81
      "label" -> if (value != null) labels.add(value) // :84-87
      "title" -> params["search"] = value // :91-93
      "milestone" -> applyMilestone(params, value) // :96-99
      "author" -> applyAuthor(params, value) // :103-111
      "assignee" -> applyAssignee(params, value, noteableType) // :116-126
      else -> params[token] = value // :129-131 passthrough
    }
  }

  private fun applyMilestone(params: MutableMap<String, String?>, value: String?) {
    params.remove("milestone")
    params["milestone_title"] = value
  }

  private fun applyAuthor(params: MutableMap<String, String?>, value: String?) {
    params.remove("author")
    if (value == "me") params["scope"] = "created-by-me" else params["author_username"] = value
  }

  private fun applyAssignee(params: MutableMap<String, String?>, value: String?, noteableType: String) {
    params.remove("assignee")
    if (value == "me") {
      params["scope"] = "assigned-to-me"
    } else {
      val key = if (noteableType == "merge_requests") "assignee_username" else "assignee_username[]"
      params[key] = value
    }
  }

  /** createQueryString: drop null values, form-urlencode, "" when empty else "?...". */
  private fun createQueryString(params: Map<String, String?>): String {
    val parts = params.entries.filter { it.value != null }.map { (k, v) ->
      "${enc(k)}=${enc(v!!)}"
    }
    return if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
  }

  private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
