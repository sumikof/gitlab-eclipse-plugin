package com.gitlab.eclipse.views.sidebar.queries

import com.google.gson.JsonObject

/**
 * The six VS Code default queries in VS Code order, with verbatim names and empty texts, plus the Eclipse `id` and
 * `target` fields (design §4, D-1 / D-2).
 */
object CustomQueryDefaults {
  private const val ISSUES = "issues"
  private const val MERGE_REQUESTS = "merge_requests"
  private const val INSTANCE = "instance"
  private const val PROJECT = "project"
  private const val PROJECT_MRS_MAX_RESULTS = 20

  /** Fresh deep copies on each access, so callers may mutate them freely. */
  val queries: List<CustomQuery>
    get() = listOf(
      query("default-issues-assigned", "Issues assigned to me", ISSUES, INSTANCE) {
        addProperty("scope", "assigned_to_me")
        addProperty("noItemText", "No issues assigned to you.")
      },
      query("default-issues-created", "Issues created by me", ISSUES, INSTANCE) {
        addProperty("scope", "created_by_me")
        addProperty("noItemText", "No issues created by you.")
      },
      query("default-mrs-assigned", "Merge requests assigned to me", MERGE_REQUESTS, INSTANCE) {
        addProperty("scope", "assigned_to_me")
        addProperty("noItemText", "No merge requests assigned to you.")
      },
      query("default-mrs-reviewing", "Merge requests I'm reviewing", MERGE_REQUESTS, INSTANCE) {
        addProperty("reviewer", "<current_user>")
        addProperty("noItemText", "No merge requests for you to review.")
      },
      query("default-mrs-created", "Merge requests created by me", MERGE_REQUESTS, INSTANCE) {
        addProperty("scope", "created_by_me")
        addProperty("noItemText", "No merge requests created by you.")
      },
      query("default-project-mrs", "All project merge requests", MERGE_REQUESTS, PROJECT) {
        addProperty("scope", "all")
        addProperty("maxResults", PROJECT_MRS_MAX_RESULTS)
        addProperty("noItemText", "The project has no merge requests")
      },
    )

  private fun query(id: String, name: String, type: String, target: String, fields: JsonObject.() -> Unit) =
    CustomQuery(
      id,
      JsonObject().apply {
        addProperty("id", id)
        addProperty("name", name)
        addProperty("type", type)
        addProperty("state", "opened")
        addProperty("target", target)
        fields()
      },
    )
}
