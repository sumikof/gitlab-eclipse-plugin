package com.gitlab.eclipse.views.sidebar.queries

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Fingerprints deciding whether a cached query result can be reused (design §7). */
object QueryFingerprints {
  private val displayOnlyFields = setOf("id", "name", "noItemText")

  /**
   * Canonical JSON (object keys sorted recursively, array order kept) of the query's stored object minus the
   * display-only fields. An invalid entry is prefixed so it never matches a valid one.
   */
  fun config(query: CustomQuery): String {
    val invalid = query.invalidElement
    if (invalid != null) return "invalid:" + canonical(invalid)
    val filtered = JsonObject()
    query.json.entrySet().filter { it.key !in displayOnlyFields }.forEach { filtered.add(it.key, it.value) }
    return "config:" + canonical(filtered)
  }

  /** The request a plan sends: path, parameters sorted by name, and the item limit. Unambiguous (JSON-encoded). */
  fun request(plan: QueryPlan.Run): String {
    val params = JsonObject()
    plan.params.toSortedMap().forEach { (k, v) -> params.addProperty(k, v) }
    val root = JsonObject()
    root.addProperty("path", plan.path)
    root.add("params", params)
    root.addProperty("maxItems", plan.maxItems)
    return root.toString()
  }

  private fun canonical(element: JsonElement): String = sorted(element).toString()

  private fun sorted(element: JsonElement): JsonElement =
    when {
      element.isJsonObject -> {
        val out = JsonObject()
        element.asJsonObject.entrySet().sortedBy { it.key }.forEach { out.add(it.key, sorted(it.value)) }
        out
      }
      element.isJsonArray -> {
        val out = JsonArray()
        element.asJsonArray.forEach { out.add(sorted(it)) }
        out
      }
      else -> element
    }
}

/**
 * Keeps the last outcome of each query so a settings change re-fetches only what changed (design §7): stage (a)
 * reuses an outcome when the config print is unchanged; stage (b) lets the caller skip applying when a re-run
 * built the same request. Accessed from the UI thread only; not thread-safe.
 */
class CustomQueryResultCache {
  private class Entry(val configPrint: String, val requestPrint: String?, val outcome: QueryOutcome)

  private val entries = HashMap<String, Entry>()

  fun clear() {
    entries.clear()
  }

  /** [requestPrint] is null when the query sent nothing (the runner's `executed` was null). */
  fun put(queryId: String, configPrint: String, requestPrint: String?, outcome: QueryOutcome) {
    entries[queryId] = Entry(configPrint, requestPrint, outcome)
  }

  /** Stage (a): the stored outcome when the query's config print is unchanged, else null. */
  fun reusableOutcome(queryId: String, configPrint: String): QueryOutcome? =
    entries[queryId]?.takeIf { it.configPrint == configPrint }?.outcome

  /** Stage (b): true only when the stored request print equals [requestPrint] and the outcome is a list result. */
  fun isRequestUnchanged(queryId: String, requestPrint: String): Boolean {
    val entry = entries[queryId] ?: return false
    return entry.requestPrint == requestPrint &&
      (entry.outcome is QueryOutcome.Issues || entry.outcome is QueryOutcome.MergeRequests)
  }

  fun retainOnly(ids: Set<String>) {
    entries.keys.retainAll(ids)
  }
}
