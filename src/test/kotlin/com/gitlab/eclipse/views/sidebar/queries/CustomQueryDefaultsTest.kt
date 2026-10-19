package com.gitlab.eclipse.views.sidebar.queries

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldNotBeSameInstanceAs

class CustomQueryDefaultsTest : StringSpec({
  data class Row(
    val id: String,
    val name: String,
    val type: String,
    val scope: String?,
    val reviewer: String?,
    val target: String,
    val maxResults: Int?,
    val noItemText: String,
  )

  fun CustomQuery.row() = Row(
    id = id,
    name = string("name")!!,
    type = string("type")!!,
    scope = string("scope"),
    reviewer = string("reviewer"),
    target = string("target")!!,
    maxResults = json.get("maxResults")?.asInt,
    noItemText = string("noItemText")!!,
  )

  "the six VS Code default queries are provided in VS Code order" {
    CustomQueryDefaults.queries.map { it.row() } shouldBe listOf(
      Row(
        "default-issues-assigned", "Issues assigned to me", "issues", "assigned_to_me", null, "instance", null,
        "No issues assigned to you.",
      ),
      Row(
        "default-issues-created", "Issues created by me", "issues", "created_by_me", null, "instance", null,
        "No issues created by you.",
      ),
      Row(
        "default-mrs-assigned", "Merge requests assigned to me", "merge_requests", "assigned_to_me", null,
        "instance", null, "No merge requests assigned to you.",
      ),
      Row(
        "default-mrs-reviewing", "Merge requests I'm reviewing", "merge_requests", null, "<current_user>",
        "instance", null, "No merge requests for you to review.",
      ),
      Row(
        "default-mrs-created", "Merge requests created by me", "merge_requests", "created_by_me", null,
        "instance", null, "No merge requests created by you.",
      ),
      Row(
        "default-project-mrs", "All project merge requests", "merge_requests", "all", null, "project", 20,
        "The project has no merge requests",
      ),
    )
  }

  "every default query is opened-state and carries its id in the stored object" {
    CustomQueryDefaults.queries.forEach {
      it.string("state") shouldBe "opened"
      it.string("id") shouldBe it.id
    }
  }

  "default ids are unique" {
    val ids = CustomQueryDefaults.queries.map { it.id }
    ids.toSet().size shouldBe ids.size
  }

  "each access returns fresh JSON objects" {
    CustomQueryDefaults.queries[0].json shouldNotBeSameInstanceAs CustomQueryDefaults.queries[0].json
  }

  "the Defaults configuration exposes the default queries" {
    QueryConfig.Defaults.queries.map { it.id } shouldBe CustomQueryDefaults.queries.map { it.id }
  }
})
