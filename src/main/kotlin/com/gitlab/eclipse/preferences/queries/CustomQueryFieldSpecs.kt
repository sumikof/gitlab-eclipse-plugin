package com.gitlab.eclipse.preferences.queries

/** One combo entry: [wire] is the form text ([CustomQueryFormModel.get] / `set`), [label] what the user sees. */
data class ComboOption(val wire: String, val label: String)

/**
 * A combo bound to one form field. Pure: the SWT dialog only shows [items] and forwards selections.
 *
 * [absentAs] is the wire value shown while the field is absent (`type` absent → merge requests) without writing it.
 * A current value that matches no option (an unsupported type, a hand-edited invalid value) is shown as an extra
 * last item so it is kept until the user picks another entry.
 */
class ComboField(private val options: List<ComboOption>, private val absentAs: String? = null) {
  /** The labels to show for the field's current form text [current]. */
  fun items(current: String): List<String> = entries(current).map { it.label }

  fun selectedIndex(current: String): Int = entries(current).indexOfFirst { it.wire == shown(current) }

  /**
   * The text to `set` after the user selected [index] of the [items] built for [initial], or null when the selection
   * does not change the [current] value.
   */
  fun textFor(index: Int, initial: String, current: String): String? {
    val wire = entries(initial).getOrNull(index)?.wire ?: return null
    return wire.takeUnless { it == shown(current) }
  }

  private fun shown(current: String): String =
    if (current.isBlank() && absentAs != null) absentAs else current.trim()

  private fun entries(current: String): List<ComboOption> {
    val shown = shown(current)
    return if (options.any { it.wire == shown }) options else options + ComboOption(shown, extraLabel(shown))
  }

  private fun extraLabel(wire: String): String = if (wire in UNSUPPORTED_TYPES) "$wire (not supported)" else wire

  private companion object {
    val UNSUPPORTED_TYPES = setOf("epics", "snippets", "vulnerabilities")
  }
}

/** Labels and combo choices of the query edit dialog, keyed by [CustomQueryFormModel.FIELDS] (design §10). */
object CustomQueryFieldSpecs {
  private const val DEFAULT = "(default)"

  private val LABELS = mapOf(
    "name" to "Name",
    "type" to "Type",
    "target" to "Search in",
    "scope" to "Scope",
    "state" to "State",
    "labels" to "Labels (comma-separated)",
    "excludeLabels" to "Exclude labels (comma-separated)",
    "milestone" to "Milestone",
    "excludeMilestone" to "Exclude milestone",
    "author" to "Author",
    "excludeAuthor" to "Exclude author",
    "assignee" to "Assignee",
    "excludeAssignee" to "Exclude assignee",
    "reviewer" to "Reviewer",
    "search" to "Search",
    "searchIn" to "Search text in",
    "excludeSearch" to "Exclude search",
    "excludeSearchIn" to "Exclude search text in",
    "createdAfter" to "Created after",
    "createdBefore" to "Created before",
    "updatedAfter" to "Updated after",
    "updatedBefore" to "Updated before",
    "draft" to "Draft",
    "confidential" to "Confidential",
    "orderBy" to "Order by",
    "sort" to "Sort",
    "maxResults" to "Max results (empty = all)",
    "noItemText" to "Empty text",
  )

  private val ANY_YES_NO = listOf(ComboOption("", "Any"), ComboOption("yes", "Yes"), ComboOption("no", "No"))
  private val SEARCH_IN = withDefault("all", "title", "description")

  private val COMBOS: Map<String, ComboField> = mapOf(
    "type" to ComboField(
      listOf(ComboOption("issues", "Issues"), ComboOption("merge_requests", "Merge requests")),
      absentAs = "merge_requests",
    ),
    "target" to ComboField(
      listOf(
        ComboOption("", "$DEFAULT Current project"),
        ComboOption("instance", "All projects"),
        ComboOption("project", "Current project"),
      ),
    ),
    "scope" to ComboField(withDefault("assigned_to_me", "created_by_me", "dismissed", "all")),
    "state" to ComboField(withDefault("all", "opened", "closed")),
    "searchIn" to ComboField(SEARCH_IN),
    "excludeSearchIn" to ComboField(SEARCH_IN),
    "draft" to ComboField(ANY_YES_NO),
    "confidential" to ComboField(ANY_YES_NO),
    "orderBy" to ComboField(
      withDefault(
        "created_at", "updated_at", "priority", "due_date", "relative_position", "label_priority", "milestone_due",
        "popularity", "weight",
      ),
    ),
    "sort" to ComboField(withDefault("asc", "desc")),
  )

  fun label(field: String): String = LABELS[field] ?: field

  /** The combo of [field], or null for a text field. */
  fun combo(field: String): ComboField? = COMBOS[field]

  private fun withDefault(vararg values: String): List<ComboOption> =
    listOf(ComboOption("", DEFAULT)) + values.map { ComboOption(it, it) }
}
